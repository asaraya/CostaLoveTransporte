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
    <div className="container" style={{ maxWidth: 1180, margin: '0 auto', padding: 20 }}>
      <h1 style={{ color: '#163E7A', marginBottom: 8 }}>Historial de cambios</h1>
      <p style={{ marginTop: 0, color: '#334155' }}>
        Consulte el historial operativo de un paquete por código de tracking.
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
        <div style={{ overflowX: 'auto', background: '#fff', border: '1px solid #e2e8f0', borderRadius: 12 }}>
          <table style={{ width: '100%', borderCollapse: 'collapse', minWidth: 980 }}>
            <thead>
              <tr style={{ background: '#f1f5f9', color: '#163E7A', textAlign: 'left' }}>
                <th style={{ padding: 12 }}>Fecha y hora</th>
                <th style={{ padding: 12 }}>Usuario</th>
                <th style={{ padding: 12 }}>Origen</th>
                <th style={{ padding: 12 }}>Acción</th>
                <th style={{ padding: 12 }}>Campo</th>
                <th style={{ padding: 12 }}>Antes</th>
                <th style={{ padding: 12 }}>Después</th>
                <th style={{ padding: 12 }}>Detalle</th>
              </tr>
            </thead>
            <tbody>
              {items.map((it) => (
                <tr key={it.id} style={{ borderTop: '1px solid #e2e8f0' }}>
                  <td style={{ padding: 12, whiteSpace: 'nowrap' }}>{fmtDate(it.fecha_hora)}</td>
                  <td style={{ padding: 12 }}>{val(it.usuario)}</td>
                  <td style={{ padding: 12 }}>{val(it.modulo_origen)}</td>
                  <td style={{ padding: 12 }}>{val(it.accion)}</td>
                  <td style={{ padding: 12 }}>{val(it.campo_afectado)}</td>
                  <td style={{ padding: 12 }}>{val(it.valor_anterior)}</td>
                  <td style={{ padding: 12 }}>{val(it.valor_nuevo)}</td>
                  <td style={{ padding: 12, minWidth: 260 }}>{val(it.descripcion)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
