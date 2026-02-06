import React, { useEffect, useMemo, useState } from 'react'
import api from '../api'

// ===== Utilidades de estado =====
const ESTADOS_INVENTARIO = new Set([
  'ENTREGADO_A_TRANSPORTISTA_LOCAL',
  'NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE',
  'ENTREGADO_A_TRANSPORTISTA_LOCAL_2DO_INTENTO',
])

const labelEstado = (e) => {
  const k = String(e || '').toUpperCase()
  switch (k) {
    case 'ENTREGADO_A_TRANSPORTISTA_LOCAL':
      return 'Entregado a transportista local'
    case 'NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE':
      return 'No entregado - Consignatario no disponible'
    case 'ENTREGADO_A_TRANSPORTISTA_LOCAL_2DO_INTENTO':
      return 'Entregado a transportista local - 2do intento'
    case 'NO_ENTREGABLE':
      return 'No entregable - Retornado a oficina local'
    default:
      return e || '-'
  }
}

const fmtDMY = (dt) => {
  if (!dt) return '-'
  try {
    const d = new Date(dt)
    return d.toLocaleDateString('es-CR', { year: 'numeric', month: '2-digit', day: '2-digit' })
  } catch {
    return String(dt)
  }
}

// Backend trabaja en TZ Costa Rica (-06:00). Para no depender del huso del navegador, mandamos offset fijo.
const toCRISO = (yyyyMmDd, hh = '00', mm = '00', ss = '00') => {
  if (!yyyyMmDd) return null
  return `${yyyyMmDd}T${hh}:${mm}:${ss}-06:00`
}

export default function Dashboard() {
  const [summary, setSummary] = useState(null)
  const [topDistritos, setTopDistritos] = useState([])
  const [loading, setLoading] = useState(false)

  const [distModal, setDistModal] = useState({
    open: false,
    distrito: null,
    rows: [],
    loading: false,
    error: null,
  })

  const hoyCR = useMemo(() => {
    const now = new Date()
    // Formato YYYY-MM-DD en CR
    const fmt = new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Costa_Rica' })
    return fmt.format(now)
  }, [])

  const cargar = async () => {
    setLoading(true)
    try {
      const [a, b] = await Promise.all([
        api.get('/dashboard/summary'),
        api.get('/dashboard/top-distritos'),
      ])
      setSummary(a.data)
      setTopDistritos(Array.isArray(b.data) ? b.data : [])
    } catch (e) {
      alert(e?.response?.data?.message || e?.message || 'Error')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { cargar() }, [])

  // Modal: paquetes del distrito (EN INVENTARIO = 3 estados)
  const openDistritoModal = async (distrito) => {
    setDistModal({ open: true, distrito, rows: [], loading: true, error: null })
    try {
      const { data } = await api.get(`/busqueda/distrito/${encodeURIComponent(distrito)}`, {
        // El conteo "Paquetes por distrito" en dashboard es EN INVENTARIO,
        // por eso el detalle debe traer EN INVENTARIO también.
        params: { estado: 'EN_INVENTARIO' }
      })

      const arr = Array.isArray(data) ? data : []
      const rows = arr
        .filter(r => ESTADOS_INVENTARIO.has(String(r?.estado || '').toUpperCase()))
        .sort((x, y) => {
          // Orden más reciente primero por received_at (si existe), sino por id
          const ax = x?.received_at ? new Date(x.received_at).getTime() : 0
          const ay = y?.received_at ? new Date(y.received_at).getTime() : 0
          if (ay !== ax) return ay - ax
          return (Number(y?.id || 0) - Number(x?.id || 0))
        })

      setDistModal((p) => ({ ...p, rows, loading: false }))
    } catch (e) {
      setDistModal((p) => ({ ...p, loading: false, error: e?.response?.data?.message || e?.message || 'Error' }))
    }
  }

  const closeDistritoModal = () => setDistModal({ open: false, distrito: null, rows: [], loading: false, error: null })

  return (
    <div style={{ padding: 16 }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap' }}>
        <h2 style={{ margin: 0 }}>Dashboard</h2>
        <button onClick={cargar} disabled={loading} style={btnPrimary}>
          {loading ? 'Actualizando…' : 'Actualizar'}
        </button>
      </div>

      <div style={{ marginTop: 16 }}>
        <h3 style={{ marginBottom: 8 }}>Resumen</h3>

        {!summary ? (
          <div style={{ opacity: 0.8 }}>{loading ? 'Cargando…' : '—'}</div>
        ) : (
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, minmax(160px, 1fr))', gap: 12 }}>
            <Kpi title="En inventario" value={summary?.inventarioActual ?? 0} />
            <Kpi title="Entregados hoy" value={summary?.entregadosHoy ?? 0} />
            <Kpi title="Devoluciones hoy" value={summary?.devolucionesHoy ?? 0} />
            <Kpi title="No entregado (pendientes)" value={summary?.noEntregadoPendiente ?? 0} />
          </div>
        )}
      </div>

      <div style={{ marginTop: 18 }}>
        <h3 style={{ marginBottom: 8 }}>Paquetes por distrito (en inventario)</h3>

        <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
          {topDistritos.length === 0 ? (
            <div style={{ opacity: 0.75 }}>Sin datos</div>
          ) : topDistritos.map((d, i) => (
            <button
              key={`${d?.distrito || i}`}
              onClick={() => openDistritoModal(d?.distrito)}
              style={btnOutline}
              title="Ver detalle"
            >
              {d?.distrito} - {d?.total}
            </button>
          ))}
        </div>
      </div>

      {distModal.open && (
        <Modal onClose={closeDistritoModal} title={`Distrito: ${distModal.distrito}`}>
          {distModal.loading ? (
            <div>Cargando…</div>
          ) : distModal.error ? (
            <div style={{ color: 'crimson' }}>{distModal.error}</div>
          ) : (
            <div style={{ maxHeight: '70vh', overflowY: 'auto' }}>
              <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                <thead>
                  <tr>
                    <th style={th}>Tracking</th>
                    <th style={th}>Marchamo</th>
                    <th style={th}>Estado</th>
                    <th style={th}>Recibido</th>
                    <th style={th}>Nombre</th>
                    <th style={th}>Teléfono</th>
                  </tr>
                </thead>
                <tbody>
                  {distModal.rows.length === 0 ? (
                    <tr><td colSpan={6} style={{ padding: 10, textAlign: 'center', opacity: 0.8 }}>Sin resultados</td></tr>
                  ) : distModal.rows.map((r, idx) => (
                    <tr key={`${r?.id || idx}`} style={{ borderTop: '1px solid rgba(0,0,0,.08)' }}>
                      <td style={td}>{r?.tracking_code ?? '-'}</td>
                      <td style={td}>{r?.marchamo ?? '-'}</td>
                      <td style={td}>{labelEstado(r?.estado)}</td>
                      <td style={td}>{fmtDMY(r?.received_at)}</td>
                      <td style={td}>{r?.recipient_name ?? '-'}</td>
                      <td style={td}>{r?.recipient_phone ?? '-'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Modal>
      )}
    </div>
  )
}

function Kpi({ title, value }) {
  return (
    <div style={{ background: '#fff', border: '1px solid rgba(0,0,0,.08)', borderRadius: 12, padding: 12 }}>
      <div style={{ opacity: 0.85, fontSize: 12 }}>{title}</div>
      <div style={{ fontSize: 28, fontWeight: 700 }}>{value ?? 0}</div>
    </div>
  )
}

function Modal({ title, onClose, children }) {
  return (
    <div style={modalBackdrop} onClick={onClose}>
      <div style={modalCard} onClick={(e) => e.stopPropagation()}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12 }}>
          <h3 style={{ margin: 0 }}>{title}</h3>
          <button onClick={onClose} style={btnOutline}>Cerrar</button>
        </div>
        <div style={{ marginTop: 12 }}>
          {children}
        </div>
      </div>
    </div>
  )
}

const btnPrimary = {
  padding: '10px 14px',
  background: '#28C76F',
  border: '0',
  color: '#fff',
  borderRadius: 10,
  cursor: 'pointer',
  fontWeight: 700,
}

const btnOutline = {
  padding: '10px 14px',
  background: '#fff',
  border: '2px solid #28C76F',
  color: '#163E7A',
  borderRadius: 10,
  cursor: 'pointer',
  fontWeight: 700,
}

const modalBackdrop = {
  position: 'fixed',
  inset: 0,
  background: 'rgba(0,0,0,.35)',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  padding: 18,
  zIndex: 999,
}

const modalCard = {
  width: 'min(1100px, 96vw)',
  background: '#fff',
  borderRadius: 14,
  boxShadow: '0 10px 30px rgba(0,0,0,.25)',
  padding: 16,
}

const th = { textAlign: 'left', padding: 10, whiteSpace: 'nowrap' }
const td = { padding: 10, whiteSpace: 'nowrap' }
