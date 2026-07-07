import { useState } from 'react'
import { api } from '../api'

function fmtDate(value) {
  if (!value) return '—'
  try {
    return new Date(value).toLocaleString('es-CR', {
      year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', second: '2-digit'
    })
  } catch {
    return String(value)
  }
}

function val(value) {
  return value === null || value === undefined || value === '' ? '—' : String(value)
}

function fallbackLabel(value) {
  if (!value) return '—'
  const map = {
    CREACION_PAQUETE: 'Creación de paquete',
    CAMBIO_ESTADO: 'Cambio de estado',
    CAMBIO_SUBTIPO_DEVOLUCION: 'Cambio de subtipo de devolución',
    CAMBIO_MENSAJERO: 'Cambio de mensajero',
    CAMBIO_MARCHAMO: 'Cambio de marchamo',
    CAMBIO_UBICACION: 'Cambio de ubicación',
    CAMBIO_DISTRITO: 'Cambio de distrito',
    ACTUALIZACION_DATOS: 'Actualización de datos',
    ACTUALIZACION_STATUS_EXTERNO: 'Actualización de status externo',
    ELIMINACION_PAQUETE: 'Eliminación de paquete',
    RECEPCION: 'Recepción',
    IMPORTACION_CONSOLIDADO: 'Importación de consolidado',
    IMPORTACION_TRACKS_CSV: 'Importación Tracks CSV',
    STATUS_EXTERNO: 'Status externo',
    CAMBIO_STATUS: 'Cambio de status',
    AVISOS: 'Avisos',
    MOVER_MUEBLES: 'Mover muebles',
    ESTADO: 'Estado',
    DEVOLUCION_SUBTIPO: 'Subtipo de devolución',
    MENSAJERO: 'Mensajero',
    PAQUETE: 'Paquete',
    DEVOLUCION: 'Devolución',
    NO_ENTREGABLE: 'No entregable',
    EN_INVENTARIO: 'En inventario',
    ENTREGADO: 'Entregado',
    PRUEBA_DE_ENTREGA: 'Prueba de entrega',
    ENTREGADO_A_TRANSPORTISTA_LOCAL: 'Entregado a transportista local',
    NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE: 'No entregado - consignatario no disponible',
    ENTREGADO_A_TRANSPORTISTA_LOCAL_2DO_INTENTO: 'Entregado a transportista local - 2do intento',
    TR_A_CA: 'TR a CA'
  }
  const raw = String(value).trim()
  if (map[raw]) return map[raw]
  if (raw.includes('__')) {
    const [estado, subtipo] = raw.split('__')
    if (estado === 'DEVOLUCION') return `Devolución (${fallbackLabel(subtipo).toLowerCase()})`
    if (estado === 'NO_ENTREGABLE') return `No entregable (${fallbackLabel(subtipo).toLowerCase()})`
  }
  return raw.toLowerCase().replaceAll('_', ' ').replace(/^[a-záéíóúñ]/, c => c.toUpperCase())
}

function text(it, field, rawField) {
  return val(it?.[field] ?? fallbackLabel(it?.[rawField]))
}

function chipStyle(action) {
  const a = String(action || '').toUpperCase()
  let bg = '#e0f2fe'
  let color = '#075985'
  if (a.includes('ESTADO')) { bg = '#dcfce7'; color = '#166534' }
  if (a.includes('ELIMINACION')) { bg = '#fee2e2'; color = '#991b1b' }
  if (a.includes('CREACION')) { bg = '#ede9fe'; color = '#5b21b6' }
  if (a.includes('MARCHAMO') || a.includes('UBICACION') || a.includes('DISTRITO')) { bg = '#fef3c7'; color = '#92400e' }
  return { background: bg, color, borderRadius: 999, padding: '5px 10px', fontWeight: 700, fontSize: 12, display: 'inline-block' }
}

const cardStyle = {
  background: '#fff',
  border: '1px solid #e2e8f0',
  borderRadius: 14,
  padding: 16,
  boxShadow: '0 1px 2px rgba(15, 23, 42, 0.06)'
}

const labelStyle = {
  color: '#64748b',
  fontSize: 12,
  fontWeight: 700,
  textTransform: 'uppercase',
  letterSpacing: 0.4,
  marginBottom: 3
}

const valueStyle = {
  color: '#0f172a',
  fontSize: 14,
  lineHeight: 1.35
}

export default function HistorialCambios() {
  const [tracking, setTracking] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [data, setData] = useState(null)

  const buscar = async (e) => {
    e.preventDefault()
    const t = tracking.trim().toUpperCase()
    if (!t) {
      setError('Ingrese un código de tracking')
      setData(null)
      return
    }
    setLoading(true)
    setError('')
    try {
      const res = await api.get(`/auditoria/paquetes/${encodeURIComponent(t)}`)
      setData(res.data)
    } catch (err) {
      setData(null)
      setError(err?.response?.data?.message || err?.response?.data?.error || err?.message || 'No se pudo consultar el historial')
    } finally {
      setLoading(false)
    }
  }

  const items = data?.items || []

  return (
    <div className="container" style={{ maxWidth: 1120, margin: '0 auto', padding: 20 }}>
      <h1 style={{ color: '#163E7A', marginBottom: 8 }}>Historial de cambios</h1>
      <p style={{ marginTop: 0, color: '#334155' }}>
        Consulte los movimientos registrados de un paquete por código de tracking.
      </p>

      <form onSubmit={buscar} style={{ display: 'flex', gap: 10, margin: '18px 0 20px' }}>
        <input
          value={tracking}
          onChange={(e) => setTracking(e.target.value)}
          placeholder="Ej: CR123"
          style={{
            flex: 1,
            padding: '12px 14px',
            border: '1px solid #cbd5e1',
            borderRadius: 10,
            fontSize: 15,
            textTransform: 'uppercase'
          }}
        />
        <button
          type="submit"
          disabled={loading}
          style={{
            background: '#163E7A',
            color: '#fff',
            border: 0,
            borderRadius: 10,
            padding: '0 18px',
            fontWeight: 700,
            cursor: loading ? 'not-allowed' : 'pointer'
          }}
        >
          {loading ? 'Buscando…' : 'Buscar'}
        </button>
      </form>

      {error && (
        <div style={{ background: '#fee2e2', color: '#991b1b', padding: 12, borderRadius: 10, marginBottom: 16 }}>
          {error}
        </div>
      )}

      {data && (
        <div style={{ marginBottom: 14, color: '#163E7A', fontWeight: 700 }}>
          Tracking: {data.tracking} · Movimientos: {data.total}
        </div>
      )}

      {data && items.length === 0 && (
        <div style={{ background: '#f8fafc', border: '1px solid #e2e8f0', padding: 16, borderRadius: 10 }}>
          No hay movimientos registrados para este paquete.
        </div>
      )}

      {items.length > 0 && (
        <div style={{ display: 'grid', gap: 12 }}>
          {items.map((it) => (
            <article key={it.id} style={cardStyle}>
              <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, alignItems: 'flex-start', marginBottom: 10 }}>
                <div>
                  <span style={chipStyle(it.accion)}>{text(it, 'accion_label', 'accion')}</span>
                  <div style={{ marginTop: 8, color: '#0f172a', fontWeight: 700 }}>
                    {val(it.descripcion_label || it.descripcion)}
                  </div>
                </div>
                <div style={{ color: '#64748b', whiteSpace: 'nowrap', fontSize: 13 }}>
                  {fmtDate(it.fecha_hora)}
                </div>
              </div>

              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(165px, 1fr))', gap: 12, marginTop: 14 }}>
                <div>
                  <div style={labelStyle}>Usuario</div>
                  <div style={valueStyle}>{val(it.usuario)}</div>
                </div>
                <div>
                  <div style={labelStyle}>Origen</div>
                  <div style={valueStyle}>{text(it, 'modulo_origen_label', 'modulo_origen')}</div>
                </div>
                <div>
                  <div style={labelStyle}>Campo</div>
                  <div style={valueStyle}>{text(it, 'campo_label', 'campo_afectado')}</div>
                </div>
                <div>
                  <div style={labelStyle}>Antes</div>
                  <div style={valueStyle}>{val(it.valor_anterior_label ?? fallbackLabel(it.valor_anterior))}</div>
                </div>
                <div>
                  <div style={labelStyle}>Después</div>
                  <div style={valueStyle}>{val(it.valor_nuevo_label ?? fallbackLabel(it.valor_nuevo))}</div>
                </div>
              </div>
            </article>
          ))}
        </div>
      )}
    </div>
  )
}
