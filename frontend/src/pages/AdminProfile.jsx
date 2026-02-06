import { useEffect, useMemo, useState } from "react";
import axios from "axios";

const api = axios.create({
  baseURL: import.meta.env.VITE_API_URL,
  withCredentials: true,
});

export default function AdminProfile() {
  const [users, setUsers] = useState([]);
  const [uForm, setUForm] = useState({ username: "", fullName: "", password: "", role: "USER" });
  const [mForm, setMForm] = useState({ mueble: "", estanterias: "" });

  // ✅ ahora permite pegar: "7" o "M 7'2"
  const [delMueble, setDelMueble] = useState("");

  const [loading, setLoading] = useState(false);
  const [msg, setMsg] = useState("");

  // ====== NUEVO: eliminación masiva de paquetes (usa endpoint bulk del backend) ======
  const [bulkDeleteText, setBulkDeleteText] = useState("");
  const [deleting, setDeleting] = useState(false);
  const [delLog, setDelLog] = useState([]);

  const fmtCRTime = (v) => {
    const d = (typeof v === "string" || typeof v === "number") ? new Date(v) : (v || new Date());
    return d.toLocaleTimeString("es-CR", {
      timeZone: "America/Costa_Rica",
      hour12: false, hour: "2-digit", minute: "2-digit", second: "2-digit"
    });
  };
  const appendDelLog = (m) => setDelLog((prev) => [`[${fmtCRTime()}] ${m}`, ...prev].slice(0, 300));

  const parseTrackings = (txt) => {
    if (!txt) return [];
    return txt
      .split(/[,\s]+/g)
      .map((s) => s.trim())
      .filter(Boolean);
  };
  const countTrackings = () => parseTrackings(bulkDeleteText).length;

  const onBulkDelete = async () => {
    const list = parseTrackings(bulkDeleteText);
    if (!list.length) { setMsg("No hay números de envío para eliminar."); return; }
    if (!confirm(`¿Eliminar ${list.length} paquete(s)? Esta acción es permanente.`)) return;

    setDeleting(true); setMsg("");
    try {
      const { data } = await api.post("/paquetes/bulk-delete", { trackings: list });

      const solicitados = Number(data?.solicitados ?? list.length);
      const eliminados = Number(data?.eliminados ?? data?.deleted ?? 0);
      const no_encontrados = Array.isArray(data?.no_encontrados) ? data.no_encontrados : [];

      appendDelLog(`🗑 Eliminados: ${eliminados}/${solicitados}`);
      if (no_encontrados.length) appendDelLog(`⚠ No encontrados (${no_encontrados.length}): ${no_encontrados.join(", ")}`);

      if (data?.ok === false) setMsg(data?.message || "Error eliminando en lote");
      else setMsg(`Eliminados: ${eliminados}/${solicitados}. No encontrados: ${no_encontrados.length}.`);
    } catch (e) {
      const msgErr = e?.response?.data?.message || e?.message || "Error eliminando en lote";
      setMsg(msgErr);
      appendDelLog(`✖ Error bulk-delete: ${msgErr}`);
    } finally {
      setDeleting(false);
    }
  };

  const onBulkKeyDown = (e) => {
    if (e.key === "Enter" && (e.ctrlKey || e.metaKey || e.shiftKey)) {
      e.preventDefault();
      onBulkDelete();
    }
  };
  // ====== FIN NUEVO ======

  // ✅ helpers formato nuevo: M <mueble>'<estanteria>
  const formatUbic = (mueble, est) => `M ${mueble}'${est}`;

  // ✅ Permite "7" o "M 7'2" o "M7'2"
  const parseMuebleNumber = (input) => {
    if (input == null) return NaN;
    const s = String(input).trim().toUpperCase();

    // Caso típico nuevo: M 10'3 / M10'3 / M-10'3
    const m1 = s.match(/M\s*[-_ ]*\s*0*(\d+)/);
    if (m1) return Number(m1[1]);

    // Si el usuario pone solo "10"
    if (/^\d+$/.test(s)) return Number(s);

    // Fallback: primer número que aparezca
    const m2 = s.match(/(\d+)/);
    return m2 ? Number(m2[1]) : NaN;
  };

  const loadUsers = async () => {
    const { data } = await api.get("/admin/users");
    setUsers(data);
  };
  useEffect(() => { loadUsers().catch(console.error); }, []);

  const onCreateUser = async (e) => {
    e.preventDefault();
    setLoading(true); setMsg("");
    try {
      await api.post("/admin/users", uForm);
      setUForm({ username: "", fullName: "", password: "", role: "USER" });
      await loadUsers();
      setMsg("Usuario creado");
    } catch (err) {
      setMsg(err?.response?.data?.message || "Error creando usuario");
    } finally { setLoading(false); }
  };

  const onDeleteUser = async (username) => {
    if (!confirm(`¿Eliminar usuario ${username}?`)) return;
    setLoading(true); setMsg("");
    try {
      const { data } = await api.delete(`/admin/users/${username}`);
      if (!data.ok) throw new Error(data.message || "No se pudo eliminar");
      await loadUsers();
      setMsg("Usuario eliminado");
    } catch (err) {
      setMsg(err?.response?.data?.message || err.message || "Error eliminando");
    } finally { setLoading(false); }
  };

  const onAddMueble = async (e) => {
    e.preventDefault();
    setLoading(true); setMsg("");

    const mNum = Number(mForm.mueble);
    const eNum = Number(mForm.estanterias);

    if (!Number.isFinite(mNum) || mNum <= 0) { setMsg("Número de mueble inválido"); setLoading(false); return; }
    if (!Number.isFinite(eNum) || eNum <= 0) { setMsg("Número de estanterías inválido"); setLoading(false); return; }

    try {
      await api.post("/admin/muebles", { mueble: mNum, estanterias: eNum });
      setMForm({ mueble: "", estanterias: "" });
      setMsg("Mueble agregado");
    } catch (err) {
      setMsg(err?.response?.data?.message || "Error agregando mueble");
    } finally { setLoading(false); }
  };

  // ✅ Preview de ubicaciones que se crearán con formato nuevo
  const previewUbics = useMemo(() => {
    const mNum = Number(mForm.mueble);
    const eNum = Number(mForm.estanterias);
    if (!Number.isFinite(mNum) || mNum <= 0) return [];
    if (!Number.isFinite(eNum) || eNum <= 0) return [];
    const n = Math.min(eNum, 50); // preview seguro
    return Array.from({ length: n }, (_, i) => formatUbic(mNum, i + 1));
  }, [mForm.mueble, mForm.estanterias]);

  // eliminar mueble (acepta "7" o "M 7'2")
  const onDeleteMueble = async (e) => {
    e.preventDefault();
    const num = parseMuebleNumber(delMueble);

    if (!Number.isFinite(num) || num <= 0) { setMsg("Número de mueble inválido (ej: 7 o M 7'2)"); return; }
    if (!confirm(`¿Eliminar el mueble ${num} y todas sus estanterías? Los paquetes se moverán a 'PENDIENTE'.`)) return;

    setLoading(true); setMsg("");
    try {
      const { data } = await api.delete(`/admin/muebles/${num}`);
      if (!data.ok) throw new Error(data.message || "No se pudo eliminar");
      setDelMueble("");
      setMsg(`Mueble ${num} eliminado. Paquetes movidos: ${data.paquetes_movidos}. Estanterías eliminadas: ${data.ubicaciones_eliminadas}.`);
    } catch (err) {
      setMsg(err?.response?.data?.message || err.message || "Error eliminando mueble");
    } finally { setLoading(false); }
  };

  return (
    <div className="container" style={{ maxWidth: 900, margin: "20px auto" }}>
      <h1>Perfil de administrador</h1>

      <div style={{ fontSize: 12, opacity: .8, marginBottom: 10 }}>
        Formato actual de ubicaciones: <b>M {"<mueble>'<estantería>"}</b> (ej: <b>M 10'3</b>)
      </div>

      {msg && <div style={{ padding: 8, border: "1px solid #ccc", marginBottom: 12 }}>{msg}</div>}

      <section style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 24 }}>
        <form onSubmit={onCreateUser} style={{ border: "1px solid #eee", padding: 16 }}>
          <h2>Crear usuario</h2>
          <div><label>Usuario</label><input value={uForm.username} onChange={e => setUForm({ ...uForm, username: e.target.value })} required /></div>
          <div><label>Nombre completo</label><input value={uForm.fullName} onChange={e => setUForm({ ...uForm, fullName: e.target.value })} required /></div>
          <div><label>Contraseña</label><input type="password" value={uForm.password} onChange={e => setUForm({ ...uForm, password: e.target.value })} required /></div>
          <div>
            <label>Rol</label>
            <select value={uForm.role} onChange={e => setUForm({ ...uForm, role: e.target.value })}>
              <option value="USER">USER</option>
              <option value="ADMIN">ADMIN</option>
            </select>
          </div>
          <button disabled={loading} type="submit">Crear</button>
        </form>

        <form onSubmit={onAddMueble} style={{ border: "1px solid #eee", padding: 16 }}>
          <h2>Agregar mueble</h2>
          <div><label>Mueble #</label><input type="number" value={mForm.mueble} onChange={e => setMForm({ ...mForm, mueble: e.target.value })} required /></div>
          <div><label># Estanterías</label><input type="number" value={mForm.estanterias} onChange={e => setMForm({ ...mForm, estanterias: e.target.value })} required /></div>

          {previewUbics.length > 0 && (
            <div style={{ marginTop: 10, padding: 10, border: "1px dashed #ddd", fontFamily: "monospace", fontSize: 12 }}>
              <div style={{ marginBottom: 6, opacity: .8 }}>Se crearán (preview):</div>
              <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 6 }}>
                {previewUbics.map((x) => <div key={x}>{x}</div>)}
              </div>
              {Number(mForm.estanterias) > 50 && (
                <div style={{ marginTop: 6, opacity: .7 }}>(mostrando solo primeras 50)</div>
              )}
            </div>
          )}

          <button disabled={loading} type="submit" style={{ marginTop: 10 }}>Agregar</button>
        </form>
      </section>

      {/* Eliminar mueble */}
      <section style={{ border: "1px solid #eee", padding: 16, marginTop: 24 }}>
        <h2>Eliminar mueble</h2>
        <form onSubmit={onDeleteMueble} style={{ display: "flex", gap: 8, alignItems: "center" }}>
          <label>Mueble</label>
          <input
            value={delMueble}
            onChange={e => setDelMueble(e.target.value)}
            placeholder={`Ej: 7 o M 7'2`}
            required
          />
          <button disabled={loading} type="submit">Eliminar mueble</button>
        </form>
        <div style={{ fontSize: 12, opacity: .8, marginTop: 8 }}>
          Los paquetes en esas ubicaciones se moverán a la ubicación <b>PENDIENTE</b>.
        </div>
      </section>

      {/* ====== NUEVO: Eliminación masiva de paquetes (bulk) ====== */}
      <section style={{ border: "1px solid #eee", padding: 16, marginTop: 24 }}>
        <h2>Eliminar paquetes (lote)</h2>
        <label style={{ display: "block", marginBottom: 6 }}>
          Números de envío a eliminar (separados por coma, espacio o salto de línea):
        </label>
        <textarea
          value={bulkDeleteText}
          onChange={(e) => setBulkDeleteText(e.target.value)}
          onKeyDown={onBulkKeyDown}
          placeholder={`HZCR12345, HZCR67890 HZCR54321
...`}
          rows={6}
          style={{ width: "100%", padding: 12, fontFamily: "monospace", fontSize: 14, resize: "vertical", minHeight: 140 }}
        />
        <div style={{ display: "flex", gap: 8, marginTop: 8 }}>
          <button onClick={onBulkDelete} disabled={deleting || countTrackings() === 0}>
            {deleting ? "Eliminando…" : "Eliminar paquetes"}
          </button>
          <button onClick={() => setBulkDeleteText("")} disabled={deleting}>Limpiar</button>
        </div>
        <div style={{ opacity: 0.7, marginTop: 4 }}>
          {countTrackings()} por eliminar
        </div>

        <div style={{ marginTop: 16 }}>
          <strong>Log</strong>
          <ul>{delLog.map((r, i) => <li key={i}>{r}</li>)}</ul>
        </div>
      </section>
      {/* ====== FIN NUEVO ====== */}

      <section style={{ marginTop: 24 }}>
        <h2>Usuarios</h2>
        <table width="100%" border="1" cellPadding="6" style={{ borderCollapse: "collapse" }}>
          <thead>
            <tr><th>ID</th><th>Usuario</th><th>Nombre</th><th>Rol</th><th>Activo</th><th>Creado</th><th></th></tr>
          </thead>
          <tbody>
            {users.map(u => (
              <tr key={u.id}>
                <td>{u.id}</td>
                <td>{u.username}</td>
                <td>{u.full_name}</td>
                <td>{u.role}</td>
                <td>{u.active ? "Sí" : "No"}</td>
                <td>{u.created_at?.replace("T", " ").replace("Z", "")}</td>
                <td>
                  <button onClick={() => onDeleteUser(u.username)} disabled={loading || u.role === 'ADMIN'}>
                    Eliminar
                  </button>
                </td>
              </tr>
            ))}
            {!users.length && <tr><td colSpan="7">Sin usuarios</td></tr>}
          </tbody>
        </table>
      </section>
    </div>
  );
}
