import { useEffect, useState } from 'react'
import { api } from '../api'

const CR_TZ = 'America/Costa_Rica'

// YYYY-MM-DD -> ISO con offset CR fijo (-06:00)
const toCRISO = (yyyyMmDd, hh = '00', mm = '00', ss = '00') => {
  if (!yyyyMmDd) return null
  return `${yyyyMmDd}T${hh}:${mm}:${ss}-06:00`
}

const ESTADO_LABEL = {
  ENTREGADO_A_TRANSPORTISTA_LOCAL: 'Entregado a transportista local',
  NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE: 'No entregado - Consignatario no disponible',
  ENTREGADO_A_TRANSPORTISTA_LOCAL_2DO_INTENTO: 'Entregado a transportista local - 2do intento',
  NO_ENTREGABLE: 'No entregable - Retornado a oficina local',
}

const labelEstado = (code) => {
  const k = String(code ?? '').toUpperCase()
  return ESTADO_LABEL[k] || (code ?? '-')
}

export default function Dashboard() {
  const [fecha, setFecha] = useState(() =>
    new Intl.DateTimeFormat('en-CA', { timeZone: CR_TZ }).format(new Date())
  )

  const [summary, setSummary] = useState(null)
  const [topDistritos, setTopDistritos] = useState([])
  const [ultimosRec, setUltimosRec] = useState([])
  const [ultimosMov, setUltimosMov] = useState([])
  const [loading, setLoading] = useState(false)

  // Modal: paquetes por distrito (solo NO_ENTREGADO...)
  const [distModal, setDistModal] = useState({
    open: false,
    distrito: '',
    rows: [],
    loading: false,
    error: null,
  })

  // Ver paquetes por fecha (por recepción REAL)
  const [fechaPF, setFechaPF] = useState(() =>
    new Intl.DateTimeFormat('en-CA', { timeZone: CR_TZ }).format(new Date())
  )
  const [tabPF, setTabPF] = useState('RECIBIDOS') // RECIBIDOS | NO_ENTREGADOS | ENTREGADOS | NO_ENTREGABLES
  const [pfData, setPfData] = useState({
    recibidos: [],
    noEntregados: [],
    entregados: [],
    noEntregables: [],
  })
  const [pfLoading, setPfLoading] = useState(false)

  const cargarTopDistritos = async () => {
    try {
      // OJO: el endpoint puede seguir llamándose "top-ubicaciones" en backend,
      // pero aquí lo tratamos como "top-distritos".
      const { data } = await api.get('/dashboard/top-distritos', { params: { limit: 100000 } })
      const arr = Array.isArray(data) ? data : []

      const normalized = arr
        .map((r) => ({
          distrito:
            r?.distrito ??
            r?.distrito_nombre ??
            r?.ubicacion ??
            r?.ubicacion_codigo ??
            r?.nombre ??
            '',
          cantidad: Number(r?.cantidad ?? r?.total ?? r?.count ?? 0) || 0,
        }))
        .filter((x) => x.distrito)

      normalized.sort((a, b) => (b.cantidad ?? 0) - (a.cantidad ?? 0))
      setTopDistritos(normalized)
    } catch (e) {
      alert(e?.response?.data?.message || e?.message || 'Error')
    }
  }

  const cargarFecha = async () => {
    setLoading(true)
    try {
      const [s, r, m] = await Promise.all([
        api.get('/dashboard/summary', { params: { fecha } }),
        api.get('/dashboard/ultimos-recibidos', { params: { limit: 10, fecha } }),
        api.get('/dashboard/ultimos-movimientos', { params: { fecha, limit: 100000 } }),
      ])

      setSummary(s.data)
      setUltimosRec(Array.isArray(r.data) ? r.data : [])

      const movSrc = Array.isArray(m.data) ? m.data : []
      const movHoy = movSrc.sort((a, b) => {
        const da = new Date(movFechaOficial(a) ?? 0).getTime()
        const db = new Date(movFechaOficial(b) ?? 0).getTime()
        return db - da
      })
      setUltimosMov(movHoy)
    } catch (e) {
      alert(e?.response?.data?.message || e?.message || 'Error')
    } finally {
      setLoading(false)
    }
  }

  const cargarTodo = async () => {
    setLoading(true)
    try {
      const [s, u, r, m] = await Promise.all([
        api.get('/dashboard/summary', { params: { fecha } }),
        api.get('/dashboard/top-distritos', { params: { limit: 100000 } }),
        api.get('/dashboard/ultimos-recibidos', { params: { limit: 10, fecha } }),
        api.get('/dashboard/ultimos-movimientos', { params: { fecha, limit: 100000 } }),
      ])

      setSummary(s.data)

      const arr = Array.isArray(u.data) ? u.data : []
      const normalized = arr
        .map((r) => ({
          distrito:
            r?.distrito ??
            r?.distrito_nombre ??
            r?.ubicacion ??
            r?.ubicacion_codigo ??
            r?.nombre ??
            '',
          cantidad: Number(r?.cantidad ?? r?.total ?? r?.count ?? 0) || 0,
        }))
        .filter((x) => x.distrito)

      normalized.sort((a, b) => (b.cantidad ?? 0) - (a.cantidad ?? 0))
      setTopDistritos(normalized)

      setUltimosRec(Array.isArray(r.data) ? r.data : [])

      const movSrc = Array.isArray(m.data) ? m.data : []
      const movHoy = movSrc.sort((a, b) => {
        const da = new Date(movFechaOficial(a) ?? 0).getTime()
        const db = new Date(movFechaOficial(b) ?? 0).getTime()
        return db - da
      })
      setUltimosMov(movHoy)
    } catch (e) {
      alert(e?.response?.data?.message || e?.message || 'Error')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { cargarTopDistritos() }, [])
  useEffect(() => { cargarFecha() }, [fecha])

  // Modal: paquetes del distrito (solo NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE)
  const openDistritoModal = async (distrito) => {
    setDistModal({ open: true, distrito, rows: [], loading: true, error: null })
    try {
      const { data } = await api.get(`/busqueda/distrito/${encodeURIComponent(distrito)}`, {
        params: { estado: 'NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE' }
      })
      const arr = Array.isArray(data) ? data : []
      const rows = arr
        .filter(r => String(r?.estado ?? '').toUpperCase() === 'NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE')
        .sort((a, b) => new Date(b?.received_at ?? 0).getTime() - new Date(a?.received_at ?? 0).getTime())

      setDistModal(prev => ({ ...prev, rows, loading: false }))
    } catch (e) {
      setDistModal(prev => ({
        ...prev,
        loading: false,
        error: e?.response?.data?.message || e?.message || 'Error cargando paquetes'
      }))
    }
  }

  const closeDistritoModal = () => setDistModal(prev => ({ ...prev, open: false }))

  // Cerrar con ESC
  useEffect(() => {
    const onKey = (e) => { if (e.key === 'Escape') closeDistritoModal() }
    if (distModal.open) window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [distModal.open])

  // Fecha lógica del movimiento según el estado destino
  function movFechaOficial(r) {
    if (!r) return null
    const to = String(r.estado_to ?? r.estadoTo ?? '').toUpperCase()

    if (to === 'NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE' && r.received_at) return r.received_at
    if ((to === 'ENTREGADO_A_TRANSPORTISTA_LOCAL' || to === 'ENTREGADO_A_TRANSPORTISTA_LOCAL_2DO_INTENTO') && r.delivered_at) return r.delivered_at
    if (to === 'NO_ENTREGABLE' && r.returned_at) return r.returned_at

    return r.changed_at ?? r.changedAt ?? null
  }

  // Paquetes por fecha (recepción REAL)
  const cargarPorFecha = async () => {
    if (!fechaPF) return
    setPfLoading(true)
    try {
      const iniISO = toCRISO(fechaPF, '00', '00', '00')
      const finISO = toCRISO(fechaPF, '23', '59', '59')

      const { data } = await api.get('/busqueda/fecha', {
        params: { tipoFecha: 'RECEPCION', desde: iniISO, hasta: finISO }
      })

      const recibidos = Array.isArray(data) ? data : []

      const entregados = recibidos.filter(r => {
        const est = String(r?.estado ?? '').toUpperCase()
        return est === 'ENTREGADO_A_TRANSPORTISTA_LOCAL' || est === 'ENTREGADO_A_TRANSPORTISTA_LOCAL_2DO_INTENTO'
      })

      const noEntregados = recibidos.filter(r =>
        String(r?.estado ?? '').toUpperCase() === 'NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE'
      )

      const noEntregables = recibidos.filter(r =>
        String(r?.estado ?? '').toUpperCase() === 'NO_ENTREGABLE'
      )

      setPfData({ recibidos, entregados, noEntregados, noEntregables })
    } catch (e) {
      alert(e?.response?.data?.message || e?.message || 'Error')
    } finally {
      setPfLoading(false)
    }
  }

  const currentPFRows = (() => {
    if (tabPF === 'ENTREGADOS') return pfData.entregados
    if (tabPF === 'NO_ENTREGADOS') return pfData.noEntregados
    if (tabPF === 'NO_ENTREGABLES') return pfData.noEntregables
    return pfData.recibidos
  })()

  const currentPFDateKey = 'received_at'

  return (
    <div>
      <h3>Dashboard</h3>

      <div style={{ display: 'flex', gap: 12, alignItems: 'center', marginBottom: 12 }}>
        <label>Fecha:
          <input type="date" value={fecha} onChange={e => setFecha(e.target.value)} />
        </label>
        <button onClick={cargarTodo} disabled={loading}>{loading ? 'Actualizando…' : 'Actualizar'}</button>
      </div>

      {/* KPIs */}
      {summary && (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 12, marginBottom: 12 }}>
          <Kpi title="Paquetes totales" value={summary.totales?.paquetes ?? summary.totalPaquetes ?? 0} />
          <Kpi title="No entregados actuales" value={summary.inventarioActual ?? 0} />
        </div>
      )}

      {/* Hoy */}
      {summary && (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 12, marginBottom: 12 }}>
          <Kpi title={`Recibidos ${summary.fecha ?? fecha}`} value={summary.hoy?.recibidos ?? 0} />
          <Kpi title={`Entregados ${summary.fecha ?? fecha}`} value={summary.hoy?.entregados ?? 0} />
          <Kpi
            title={`No entregables ${summary.fecha ?? fecha}`}
            value={summary.hoy?.noEntregable ?? summary.hoy?.no_entregable ?? summary.hoy?.devoluciones ?? 0}
          />
        </div>
      )}

      {/* Por estado */}
      {summary && (
        <div style={{ marginBottom: 16 }}>
          <h4>Paquetes por estado</h4>
          <table border="1" cellPadding="6" width="100%">
            <thead><tr><th>Estado</th><th>Cantidad</th></tr></thead>
            <tbody>
              {summary.byEstado?.map((r, i) => (
                <tr key={i}><td>{labelEstado(r.estado)}</td><td>{r.cantidad}</td></tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
        <div>
          {/* ✅ CAMBIO PEDIDO: título */}
          <h4>Paquetes por distrito</h4>

          <table border="1" cellPadding="6" width="100%">
            <thead><tr><th>Distrito</th><th>Cantidad</th></tr></thead>
            <tbody>
              {topDistritos.map((r, i) => (
                <tr key={i}>
                  <td>
                    <button
                      onClick={() => openDistritoModal(r.distrito)}
                      style={{
                        background: 'none', border: 'none', color: '#0b66c3',
                        textDecoration: 'underline', padding: 0, cursor: 'pointer'
                      }}
                      title="Ver paquetes en este distrito"
                    >
                      {r.distrito}
                    </button>
                  </td>
                  <td>{r.cantidad}</td>
                </tr>
              ))}
              {!topDistritos.length && (
                <tr><td colSpan={2} style={{ textAlign: 'center', opacity: .7 }}>Sin datos</td></tr>
              )}
            </tbody>
          </table>
        </div>

        <div>
          <h4>Últimos recibidos</h4>
          <table border="1" cellPadding="6" width="100%">
            <thead>
              <tr>
                <th>Tracking</th>
                <th>Marchamo</th>
                <th>Distrito</th>
                <th>Recibido (real)</th>
                <th>Último cambio</th>
                <th>Estado</th>
              </tr>
            </thead>
            <tbody>
              {ultimosRec.map((r) => (
                <tr key={r.id}>
                  <td>{r.tracking_code}</td>
                  <td>{r.marchamo}</td>
                  <td>{r.distrito_nombre ?? '-'}</td>
                  <td>{fmtDT(r.received_at)}</td>
                  <td>{fmtDT(r.last_state_change_at ?? r.changed_at ?? r.changedAt)}</td>
                  <td>{labelEstado(r.estado)}</td>
                </tr>
              ))}
              {!ultimosRec.length && (
                <tr><td colSpan={6} style={{ textAlign: 'center', opacity: .7 }}>Sin datos</td></tr>
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* Ver paquetes por fecha */}
      <div style={{ marginTop: 16 }}>
        <h4>Ver paquetes por fecha (según recepción REAL)</h4>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 8, flexWrap: 'wrap' }}>
          <label>Fecha:
            <input type="date" value={fechaPF} onChange={e => setFechaPF(e.target.value)} style={{ marginLeft: 4 }} />
          </label>
          <button onClick={cargarPorFecha} disabled={pfLoading || !fechaPF}>
            {pfLoading ? 'Cargando…' : 'Ver'}
          </button>

          <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap' }}>
            <button onClick={() => setTabPF('RECIBIDOS')} style={tabBtnStyle(tabPF === 'RECIBIDOS')}>
              Recibidos ({pfData.recibidos.length})
            </button>
            <button onClick={() => setTabPF('NO_ENTREGADOS')} style={tabBtnStyle(tabPF === 'NO_ENTREGADOS')}>
              No entregados ({pfData.noEntregados.length})
            </button>
            <button onClick={() => setTabPF('ENTREGADOS')} style={tabBtnStyle(tabPF === 'ENTREGADOS')}>
              Entregados ({pfData.entregados.length})
            </button>
            <button onClick={() => setTabPF('NO_ENTREGABLES')} style={tabBtnStyle(tabPF === 'NO_ENTREGABLES')}>
              No entregables ({pfData.noEntregables.length})
            </button>
          </div>
        </div>

        <div style={{ border: '1px solid rgba(22,62,122,.15)', borderRadius: 8, padding: 8 }}>
          <div style={{ maxHeight: 340, overflowY: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr>
                  <th style={th}>Tracking</th>
                  <th style={th}>Marchamo</th>
                  <th style={th}>Distrito</th>
                  <th style={th}>Nombre</th>
                  <th style={th}>Descripción</th>
                  <th style={th}>Estado actual</th>
                  <th style={th}>Fecha recepción (real)</th>
                </tr>
              </thead>
              <tbody>
                {currentPFRows.length === 0 ? (
                  <tr>
                    <td colSpan={7} style={{ padding: 12, textAlign: 'center', opacity: .7 }}>
                      Sin resultados para la fecha seleccionada
                    </td>
                  </tr>
                ) : currentPFRows.map((r, idx) => (
                  <tr key={r.id ?? r.tracking_code ?? idx} style={{ borderBottom: '1px solid rgba(0,0,0,0.06)' }}>
                    <td style={td}>{r.tracking_code}</td>
                    <td style={td}>{r.marchamo}</td>
                    <td style={td}>{r.distrito_nombre ?? '-'}</td>
                    <td style={td}>{r.recipient_name ?? '-'}</td>
                    <td style={td}>{r.content_description ?? '-'}</td>
                    <td style={td}>{labelEstado(r.estado)}</td>
                    <td style={td}>{fmtDT(r[currentPFDateKey])}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </div>

      {/* Movimientos */}
      <div style={{ marginTop: 16 }}>
        <h4>Movimientos de estado del {fecha}</h4>
        <div style={{ maxHeight: 340, overflowY: 'auto' }}>
          <table border="1" cellPadding="6" width="100%">
            <thead>
              <tr>
                <th>Tracking</th><th>Marchamo</th><th>Distrito</th>
                <th>De</th><th>A</th><th>Fecha</th><th>Motivo</th><th>Por</th>
              </tr>
            </thead>
            <tbody>
              {ultimosMov.map((r, i) => (
                <tr key={r.hist_id ?? r.id ?? i}>
                  <td>{r.tracking_code}</td>
                  <td>{r.marchamo}</td>
                  <td>{r.distrito_nombre ?? '-'}</td>
                  <td>{labelEstado(r.estado_from ?? '-')}</td>
                  <td>{labelEstado(r.estado_to ?? '-')}</td>
                  <td>{fmtDT(movFechaOficial(r))}</td>
                  <td>{r.motivo ?? '-'}</td>
                  <td>{r.changed_by ?? '-'}</td>
                </tr>
              ))}
              {!ultimosMov.length && (
                <tr><td colSpan={8} style={{ textAlign: 'center', opacity: .7 }}>Sin movimientos para la fecha</td></tr>
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* MODAL: Paquetes por distrito (solo NO_ENTREGADO...) */}
      {distModal.open && (
        <div
          onClick={closeDistritoModal}
          style={{
            position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.35)',
            display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1000
          }}
        >
          <div onClick={e => e.stopPropagation()} style={blueCard}>
            <button onClick={closeDistritoModal} aria-label="Cerrar" title="Cerrar" style={closeBtn}>×</button>

            <h4 style={{ margin: '0 0 10px', color: '#fff' }}>
              Paquetes en: <span style={{ fontWeight: 800 }}>{distModal.distrito}</span>
              <span style={pill}>NO ENTREGADO</span>
            </h4>

            {distModal.loading && <div style={{ padding: 8, color: '#e8f0ff' }}>Cargando paquetes…</div>}
            {distModal.error && <div style={{ padding: 8, color: '#ffdde0' }}>{distModal.error}</div>}

            {!distModal.loading && !distModal.error && (
              <>
                <div style={{ marginBottom: 8, opacity: .9, color: '#e8f0ff' }}>
                  Total: {distModal.rows.length}
                </div>

                <div style={{ overflow: 'auto', maxHeight: '65vh', border: '1px solid rgba(255,255,255,.35)', borderRadius: 8, background: '#fff' }}>
                  <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                    <thead>
                      <tr style={{ background: '#f3f7ff' }}>
                        <th style={th}>Tracking</th>
                        <th style={th}>Marchamo</th>
                        <th style={th}>Distrito</th>
                        <th style={th}>Estado</th>
                        <th style={th}>Recibido</th>
                      </tr>
                    </thead>
                    <tbody>
                      {distModal.rows.length ? distModal.rows.map((r, idx) => (
                        <tr key={r.id ?? r.tracking_code ?? idx} style={{ borderBottom: '1px solid rgba(0,0,0,0.06)' }}>
                          <td style={td}>{r.tracking_code}</td>
                          <td style={td}>{r.marchamo}</td>
                          <td style={td}>{r.distrito_nombre ?? '-'}</td>
                          <td style={td}>{labelEstado(r.estado)}</td>
                          <td style={td}>{fmtDT(r.received_at)}</td>
                        </tr>
                      )) : (
                        <tr><td colSpan={5} style={{ padding: 12, textAlign: 'center', opacity: .7 }}>Sin resultados</td></tr>
                      )}
                    </tbody>
                  </table>
                </div>
              </>
            )}
          </div>
        </div>
      )}
    </div>
  )
}

const th = { textAlign: 'left', padding: '8px 10px', borderBottom: '1px solid #dfe7f3', position: 'sticky', top: 0 }
const td = { padding: '8px 10px', verticalAlign: 'top' }

const blueCard = {
  background: 'var(--brand-blue)',
  color: '#fff',
  borderRadius: 12,
  padding: 16,
  width: 'min(900px, 95vw)',
  maxHeight: '80vh',
  boxShadow: '0 10px 30px rgba(0,0,0,0.25)',
  position: 'relative',
  border: '2px solid rgba(255,255,255,.2)'
}

const closeBtn = {
  position: 'absolute',
  top: 8,
  right: 10,
  border: '2px solid rgba(255,255,255,.6)',
  background: 'transparent',
  color: '#fff',
  fontSize: 22,
  lineHeight: 1,
  cursor: 'pointer',
  borderRadius: 8,
  width: 36,
  height: 36
}

const pill = {
  marginLeft: 8,
  background: 'var(--brand-green)',
  color: '#fff',
  borderRadius: 999,
  padding: '2px 8px',
  fontSize: 12,
  fontWeight: 700,
  verticalAlign: 'middle',
  display: 'inline-block'
}

const tabBtnStyle = (active) => ({
  padding: '6px 10px',
  border: '1px solid #28C76F',
  background: active ? '#f6fff9' : '#ffffff',
  color: '#163E7A',
  borderRadius: 8,
  fontSize: 12,
  fontWeight: 600,
  cursor: 'pointer'
})

function Kpi({ title, value }) {
  return (
    <div style={{ background: '#ffffffff', border: '1px solid rgba(22,62,122,.12)', borderRadius: 10, padding: 12 }}>
      <div style={{ opacity: .7, fontSize: 12 }}>{title}</div>
      <div style={{ fontSize: 28, fontWeight: 700 }}>{value ?? 0}</div>
    </div>
  )
}

function fmtDT(dt) {
  if (!dt) return '-'
  try {
    const d = new Date(dt)
    return d.toLocaleString('es-CR', { timeZone: 'America/Costa_Rica' })
  } catch {
    return String(dt)
  }
}
