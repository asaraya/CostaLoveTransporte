import { useEffect, useState } from "react";
import axios from "axios";

const api = axios.create({
  baseURL: import.meta.env.VITE_API_URL,
  withCredentials: true,
});

export default function AdminProfile() {
  const [users, setUsers] = useState([]);
  const [uForm, setUForm] = useState({
    username: "",
    fullName: "",
    password: "",
    role: "USER",
  });

  // ✅ Distritos (reemplaza agregar/eliminar mueble)
  const [distritos, setDistritos] = useState([]);
  const [dForm, setDForm] = useState({ nombre: "" });
  const [delDistrito, setDelDistrito] = useState("");

  const [loading, setLoading] = useState(false);
  const [msg, setMsg] = useState("");

  // ====== Eliminación masiva de paquetes (usa endpoint bulk del backend) ======
  const [bulkDeleteText, setBulkDeleteText] = useState("");
  const [deleting, setDeleting] = useState(false);
  const [delLog, setDelLog] = useState([]);

  const fmtCRTime = (v) => {
    const d = typeof v === "string" || typeof v === "number" ? new Date(v) : v || new Date();
    return d.toLocaleTimeString("es-CR", {
      timeZone: "America/Costa_Rica",
      hour12: false,
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
    });
  };
  const appendDelLog = (m) =>
    setDelLog((prev) => [`[${fmtCRTime()}] ${m}`, ...prev].slice(0, 300));

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
    if (!list.length) {
      setMsg("No hay números de envío para eliminar.");
      return;
    }
    if (!confirm(`¿Eliminar ${list.length} paquete(s)? Esta acción es permanente.`)) return;

    setDeleting(true);
    setMsg("");
    try {
      const { data } = await api.post("/paquetes/bulk-delete", { trackings: list });

      const solicitados = Number(data?.solicitados ?? list.length);
      const eliminados = Number(data?.eliminados ?? data?.deleted ?? 0);
      const no_encontrados = Array.isArray(data?.no_encontrados) ? data.no_encontrados : [];

      appendDelLog(`🗑 Eliminados: ${eliminados}/${solicitados}`);
      if (no_encontrados.length)
        appendDelLog(`⚠ No encontrados (${no_encontrados.length}): ${no_encontrados.join(", ")}`);

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

  const loadUsers = async () => {
    const { data } = await api.get("/admin/users");
    setUsers(data);
  };

  const loadDistritos = async () => {
    const { data } = await api.get("/distritos");
    setDistritos(Array.isArray(data) ? data : []);
  };

  useEffect(() => {
    Promise.all([loadUsers(), loadDistritos()]).catch(console.error);
  }, []);

  const onCreateUser = async (e) => {
    e.preventDefault();
    setLoading(true);
    setMsg("");
    try {
      await api.post("/admin/users", uForm);
      setUForm({ username: "", fullName: "", password: "", role: "USER" });
      await loadUsers();
      setMsg("Usuario creado");
    } catch (err) {
      setMsg(err?.response?.data?.message || "Error creando usuario");
    } finally {
      setLoading(false);
    }
  };

  const onDeleteUser = async (username) => {
    if (!confirm(`¿Eliminar usuario ${username}?`)) return;
    setLoading(true);
    setMsg("");
    try {
      const { data } = await api.delete(`/admin/users/${username}`);
      if (data?.ok === false) throw new Error(data?.message || "No se pudo eliminar");
      await loadUsers();
      setMsg("Usuario eliminado");
    } catch (err) {
      setMsg(err?.response?.data?.message || err.message || "Error eliminando");
    } finally {
      setLoading(false);
    }
  };

  // ===== Distritos =====

  const normalizeDistrito = (s) => (s ?? "").trim();

  const onAddDistrito = async (e) => {
    e.preventDefault();
    setLoading(true);
    setMsg("");

    const nombre = normalizeDistrito(dForm.nombre);
    if (!nombre) {
      setMsg("Nombre de distrito inválido");
      setLoading(false);
      return;
    }

    try {
      await api.post("/admin/distritos", { nombre });
      setDForm({ nombre: "" });
      await loadDistritos();
      setMsg("Distrito agregado");
    } catch (err) {
      setMsg(err?.response?.data?.message || "Error agregando distrito");
    } finally {
      setLoading(false);
    }
  };

  const onDeleteDistrito = async (e) => {
    e.preventDefault();
    const nombre = normalizeDistrito(delDistrito);

    if (!nombre) {
      setMsg("Nombre de distrito inválido");
      return;
    }
    if (!confirm(`¿Eliminar el distrito "${nombre}"?`)) return;

    setLoading(true);
    setMsg("");
    try {
      const { data } = await api.delete(`/admin/distritos/${encodeURIComponent(nombre)}`);
      if (data?.ok === false) throw new Error(data?.message || "No se pudo eliminar");

      setDelDistrito("");
      await loadDistritos();
      setMsg(`Distrito "${nombre}" eliminado`);
    } catch (err) {
      setMsg(err?.response?.data?.message || err.message || "Error eliminando distrito");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="container" style={{ maxWidth: 900, margin: "20px auto" }}>
      <h1>Perfil de administrador</h1>

      {msg && <div style={{ padding: 8, border: "1px solid #ccc", marginBottom: 12 }}>{msg}</div>}

      <section style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 24 }}>
        <form onSubmit={onCreateUser} style={{ border: "1px solid #eee", padding: 16 }}>
          <h2>Crear usuario</h2>
          <div>
            <label>Usuario</label>
            <input
              value={uForm.username}
              onChange={(e) => setUForm({ ...uForm, username: e.target.value })}
              required
            />
          </div>
          <div>
            <label>Nombre completo</label>
            <input
              value={uForm.fullName}
              onChange={(e) => setUForm({ ...uForm, fullName: e.target.value })}
              required
            />
          </div>
          <div>
            <label>Contraseña</label>
            <input
              type="password"
              value={uForm.password}
              onChange={(e) => setUForm({ ...uForm, password: e.target.value })}
              required
            />
          </div>
          <div>
            <label>Rol</label>
            <select value={uForm.role} onChange={(e) => setUForm({ ...uForm, role: e.target.value })}>
              <option value="USER">USER</option>
              <option value="ADMIN">ADMIN</option>
            </select>
          </div>
          <button disabled={loading} type="submit">
            Crear
          </button>
        </form>

        <form onSubmit={onAddDistrito} style={{ border: "1px solid #eee", padding: 16 }}>
          <h2>Agregar distrito</h2>
          <div>
            <label>Nombre</label>
            <input
              value={dForm.nombre}
              onChange={(e) => setDForm({ nombre: e.target.value })}
              placeholder="Ej: La colonia"
              required
            />
          </div>
          <button disabled={loading} type="submit" style={{ marginTop: 10 }}>
            Agregar
          </button>
        </form>
      </section>

      {/* Eliminar distrito */}
      <section style={{ border: "1px solid #eee", padding: 16, marginTop: 24 }}>
        <h2>Eliminar distrito</h2>
        <form onSubmit={onDeleteDistrito} style={{ display: "flex", gap: 8, alignItems: "center" }}>
          <label>Distrito</label>
          <input
            value={delDistrito}
            onChange={(e) => setDelDistrito(e.target.value)}
            placeholder="Ej: Roxana"
            required
          />
          <button disabled={loading} type="submit">
            Eliminar distrito
          </button>
        </form>
      </section>

      {/* Lista de distritos */}
      <section style={{ marginTop: 24 }}>
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 12 }}>
          <h2 style={{ margin: 0 }}>Distritos</h2>
          <button onClick={loadDistritos} disabled={loading}>
            Recargar
          </button>
        </div>

        <table width="100%" border="1" cellPadding="6" style={{ borderCollapse: "collapse", marginTop: 8 }}>
          <thead>
            <tr>
              <th>ID</th>
              <th>Nombre</th>
              <th>Activo</th>
              <th>Creado</th>
            </tr>
          </thead>
          <tbody>
            {distritos.map((d) => (
              <tr key={d.id}>
                <td>{d.id}</td>
                <td>{d.nombre}</td>
                <td>{d.activo ? "Sí" : "No"}</td>
                <td>{String(d.created_at ?? "").replace("T", " ").replace("Z", "")}</td>
              </tr>
            ))}
            {!distritos.length && (
              <tr>
                <td colSpan="4">Sin distritos</td>
              </tr>
            )}
          </tbody>
        </table>
      </section>

      {/* ====== Eliminación masiva de paquetes (bulk) ====== */}
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
          style={{
            width: "100%",
            padding: 12,
            fontFamily: "monospace",
            fontSize: 14,
            resize: "vertical",
            minHeight: 140,
          }}
        />
        <div style={{ display: "flex", gap: 8, marginTop: 8 }}>
          <button onClick={onBulkDelete} disabled={deleting || countTrackings() === 0}>
            {deleting ? "Eliminando…" : "Eliminar paquetes"}
          </button>
          <button onClick={() => setBulkDeleteText("")} disabled={deleting}>
            Limpiar
          </button>
        </div>
        <div style={{ opacity: 0.7, marginTop: 4 }}>{countTrackings()} por eliminar</div>

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
            <tr>
              <th>ID</th>
              <th>Usuario</th>
              <th>Nombre</th>
              <th>Rol</th>
              <th>Activo</th>
              <th>Creado</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {users.map((u) => (
              <tr key={u.id}>
                <td>{u.id}</td>
                <td>{u.username}</td>
                <td>{u.full_name}</td>
                <td>{u.role}</td>
                <td>{u.active ? "Sí" : "No"}</td>
                <td>{u.created_at?.replace("T", " ").replace("Z", "")}</td>
                <td>
                  <button onClick={() => onDeleteUser(u.username)} disabled={loading || u.role === "ADMIN"}>
                    Eliminar
                  </button>
                </td>
              </tr>
            ))}
            {!users.length && (
              <tr>
                <td colSpan="7">Sin usuarios</td>
              </tr>
            )}
          </tbody>
        </table>
      </section>
    </div>
  );
}
