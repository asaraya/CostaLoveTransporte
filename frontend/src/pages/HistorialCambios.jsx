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

function humanize(value) {
  if (!value) return '—'
  const raw = String(value).trim()
  return raw.toLowerCase().replaceAll('_', ' ').replace(/^[a-záéíóúñ]/, c => c.toUpperCase())
}

function labelSubtipo(value) {
  const raw = String(value || '').trim().toUpperCase()
  const map = {
    FUERA_DE_RUTA: 'Fuera de ruta',
    VENCIDOS: 'Vencidos',
    DOS_INTENTOS: 'Dos intentos',
    ENRUTE: 'En ruta',
    OTRAS_ZONAS: 'Otras zonas',
    NO_ENTREGAR: 'No entregar',
    TRANSPORTE: 'Transporte'
  }
  return map[raw] || humanize(raw)
}

function label(value) {
  if (!value) return '—'
  const map = {
    CREACION_PAQUETE: 'Creación de paquete',
    CAMBIO_ESTADO: 'Cambio de estado',
    CAMBIO_SUBTIPO_DEVOLUCION: 'Cambio de subtipo de no entregable',
    ASIGNACION_MENSAJERO: 'Detalle de prueba de entrega',
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
    ENTREGAS: 'Entregas',
    AVISOS: 'Avisos',
    INVENTARIO: 'Inventario',
    MOVER_MUEBLES: 'Mover muebles',
    HISTORIAL_EXISTENTE: 'Historial existente',
    ADMIN: 'Administración',
    ESTADO: 'Estado',
    DEVOLUCION_SUBTIPO: 'Subtipo de no entregable',
    MENSAJERO: 'Detalle de prueba de entrega',
    PAQUETE: 'Paquete',
    PRUEBA_DE_ENTREGA: 'Prueba de entrega',
    ENTREGADO_A_TRANSPORTISTA_LOCAL: 'Entregado a transportista local',
    NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE: 'No entregado - consignatario no disponible',
    ENTREGADO_A_TRANSPORTISTA_LOCAL_2DO_INTENTO: 'Entregado a transportista local - 2do intento',
    NO_ENTREGABLE: 'No entregable',
    TR_A_CA: 'TR a CA'
  }
  const raw = String(value).trim()
  if (map[raw]) return map[raw]
  if (raw.includes('__')) {
    const [estado, subtipo] = raw.split('__')
    if (estado === 'NO_ENTREGABLE') return `No entregable (${labelSubtipo(subtipo)})`
    if (estado === 'DEVOLUCION') return `Devolución (${labelSubtipo(subtipo)})`
  }
  return humanize(raw)
}

function chipStyle(action) {
  const a = String(action || '').toUpperCase()
  let bg = '#e0f2fe'
  let color = '#075985'
  if (a.includes('ESTADO') || a.includes('SUBTIPO')) { bg = '#dcfce7'; color = '#166534' }
  if (a.includes('MENSAJERO')) { bg = '#e0f2fe'; color = '#075985' }
  if (a.includes('ELIMINACION')) { bg = '#fee2e2'; color = '#991b1b' }
  if (a.includes('CREACION')) { bg = '#ede9fe'; color = '#5b21b6' }
  return { background: bg, color, borderRadius: 999, padding: '5px 10px', fontWeight: 700, fontSize: 12, display: 'inline-block' }
}

const cardStyle = {
  background: '#fff',
  border: '1px solid #dbe3ef',
  borderRadius: 14,
  padding: 16,
  boxShadow: '0 2px 8px rgba(15, 23, 42, 0.06)'
}

const rowStyle = { marginTop: 6, color: '#0f172a', lineHeight: 1.45 }
const labelStyle = { fontWeight: 800, color: '#163E7A' }

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
    <div className="container" style={{ maxWidth: 1050, margin: '0 auto', padding: 20 }}>
      <h1 style={{ color: '#163E7A', marginBottom: 8 }}>Historial de cambios</h1>
      <p style={{ marginTop: 0, color: '#334155' }}>
        Ingrese un tracking para ver todos los movimientos registrados del paquete.
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
              <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, alignItems: 'flex-start' }}>
                <div style={{ fontWeight: 800, color: '#0f172a' }}>{fmtDate(it.fecha_hora)}</div>
                <span style={chipStyle(it.accion)}>{val(it.accion_label || label(it.accion))}</span>
              </div>

              <div style={rowStyle}><span style={labelStyle}>Usuario:</span> {val(it.usuario)}</div>
              <div style={rowStyle}><span style={labelStyle}>Origen:</span> {val(it.modulo_origen_label || label(it.modulo_origen))}</div>
              <div style={rowStyle}><span style={labelStyle}>Acción:</span> {val(it.accion_label || label(it.accion))}</div>
              <div style={rowStyle}><span style={labelStyle}>Detalle:</span> {val(it.descripcion_label || it.descripcion)}</div>

              {(it.campo_afectado || it.valor_anterior || it.valor_nuevo) && (
                <div style={{ marginTop: 10, paddingTop: 10, borderTop: '1px solid #e2e8f0', color: '#334155' }}>
                  <div style={rowStyle}><span style={labelStyle}>Campo:</span> {val(it.campo_label || label(it.campo_afectado))}</div>
                  <div style={rowStyle}><span style={labelStyle}>Antes:</span> {val(it.valor_anterior_label || label(it.valor_anterior))}</div>
                  <div style={rowStyle}><span style={labelStyle}>Después:</span> {val(it.valor_nuevo_label || label(it.valor_nuevo))}</div>
                </div>
              )}
            </article>
          ))}
        </div>
      )}
    </div>
  )
}
