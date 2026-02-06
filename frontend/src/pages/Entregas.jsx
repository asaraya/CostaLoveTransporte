import { useMemo, useState } from 'react'
import { api, toastErr, toastOk } from '../api'

// Zona horaria de Costa Rica
const CR_TZ = 'America/Costa_Rica'

// Fecha hoy en CR como YYYY-MM-DD
function todayCR() {
  return new Intl.DateTimeFormat('en-CA', { timeZone: CR_TZ }).format(new Date())
}

// Convierte YYYY-MM-DD (CR) a ISO estable (usa 12:00 CR para evitar líos de zona)
function ymdToCRNoonISO(ymd) {
  const d = new Date(`${ymd}T12:00:00-06:00`)
  return d.toISOString()
}

// Parser: separa por coma/espacio/salto de línea, uppercase, sin duplicados, y filtra HZCR/CR
function parseTokens(raw) {
  if (!raw) return []
  const tokens = raw
    .split(/[\s,;]+/g)
    .map(s => s.trim().toUpperCase())
    .filter(Boolean)

  const seen = new Set()
  const out = []
  const re = /^(HZCR|CR)\d+$/i

  for (const t of tokens) {
    if (re.test(t) && !seen.has(t)) {
      seen.add(t)
      out.push(t)
    }
  }
  return out
}

export default function Entregas() {
  const [raw, setRaw] = useState('')
  const trackings = useMemo(() => parseTokens(raw), [raw])

  // fecha del cambio de estado
  const [fecha, setFecha] = useState(todayCR())

  // Estados del backend NUEVO
  const ESTADOS = [
    { val: 'NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE', label: 'En inventario (disponible)' },
    { val: 'ENTREGADO_A_TRANSPORTISTA_LOCAL', label: 'Entregado a transportista local' },
    { val: 'ENTREGADO_A_TRANSPORTISTA_LOCAL_2DO_INTENTO', label: 'Entregado a transportista local (2do intento)' },
    { val: 'NO_ENTREGABLE', label: 'No entregable' },
  ]
  const [nuevoEstado, setNuevoEstado] = useState(ESTADOS[0].val)

  // Subcategorías (solo aplica si estado = NO_ENTREGABLE)
  const SUBS = [
    { val: 'FUERA_DE_RUTA', label: 'Fuera de ruta' },
    { val: 'VENCIDOS', label: 'Vencidos' },
    { val: 'DOS_INTENTOS', label: 'Dos intentos' },
  ]
  const [devolSub, setDevolSub] = useState(SUBS[0].val)

  const [loading, setLoading] = useState(false)
  const [log, setLog] = useState([])
  const appendLog = (line) => setLog(prev => [...prev, line])

  const existeTracking = async (t) => {
    try {
      const { data } = await api.get('/busqueda/tracking', { params: { q: t, like: 0 } })
      return Array.isArray(data) && data.length > 0
    } catch {
      return false
    }
  }

  const onAplicar = async () => {
    try {
      if (!trackings.length) { appendLog('⚠️ Ingrese al menos un tracking.'); return }

      setLoading(true)
      setLog([])

      const whenISO = ymdToCRNoonISO(fecha)

      // Validación informativa (no bloquea)
      const inexistentes = []
      for (const t of trackings) {
        // eslint-disable-next-line no-await-in-loop
        const ok = await existeTracking(t)
        if (!ok) inexistentes.push(t)
      }
      if (inexistentes.length) appendLog(`⚠️ No existen: ${inexistentes.join(', ')}`)

      let okCount = 0
      let failCount = 0

      for (const t of trackings) {
        if (inexistentes.includes(t)) { failCount++; continue }

        try {
          // eslint-disable-next-line no-await-in-loop
          await api.post('/estado/tracking', {
            tracking: t,
            estado: nuevoEstado,
            motivo: 'Cambio desde Entregas',
            devolucionSubtipo: (nuevoEstado === 'NO_ENTREGABLE' ? devolSub : undefined),
            when: whenISO,
          })

          okCount++
          appendLog(
            `✅ ${t} → ${nuevoEstado}` +
            (nuevoEstado === 'NO_ENTREGABLE' ? ` (${devolSub})` : '') +
            ` (fecha ${fecha})`
          )
        } catch (e) {
          failCount++
          appendLog(`❌ ${t}: ${e?.response?.data?.message || e.message}`)
        }
      }

      toastOk(`Procesados: ${okCount} ok, ${failCount} con error`)
    } catch (e) {
      toastErr(e)
    } finally {
      setLoading(false)
    }
  }

  const estadoBtn = (opt) => (
    <button
      key={opt.val}
      type="button"
      className={`toggle ${nuevoEstado === opt.val ? 'is-selected' : ''}`}
      aria-pressed={nuevoEstado === opt.val}
      onClick={() => setNuevoEstado(opt.val)}
    >
      {opt.label}
    </button>
  )

  return (
    <div className="page">
      <h2 style={{ marginBottom: 12 }}>Cambio de Status</h2>

      <div style={{ marginBottom: 12 }}>
        <label style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <span>Fecha del cambio de estado:</span>
          <input
            type="date"
            value={fecha}
            onChange={(e) => setFecha(e.target.value)}
            style={{ padding: 8, width: 220 }}
          />
        </label>
        <div style={{ fontSize: 12, opacity: .75, marginTop: 4 }}>
          * Por defecto es la fecha actual (zona horaria Costa Rica).
        </div>
      </div>

      <div style={{ display: 'flex', gap: 12, alignItems: 'center', flexWrap: 'wrap', marginBottom: 8 }}>
        <label>Estado a aplicar:</label>
        {ESTADOS.map(estadoBtn)}
      </div>

      {nuevoEstado === 'NO_ENTREGABLE' && (
        <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap', marginBottom: 8 }}>
          <label>Subcategoría:</label>
          {SUBS.map(s => (
            <button
              key={s.val}
              type="button"
              className={`toggle ${devolSub === s.val ? 'is-selected' : ''}`}
              aria-pressed={devolSub === s.val}
              onClick={() => setDevolSub(s.val)}
            >
              {s.label}
            </button>
          ))}
        </div>
      )}

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
        <div style={{ border: '1px solid #163E7A', borderRadius: 8, padding: 12 }}>
          <div style={{ marginBottom: 8 }}>
            <label>Trackings (coma, espacio o salto de línea):</label>
            <textarea
              rows={8}
              placeholder="HZCR12345 CR98765 ..."
              value={raw}
              onChange={(e) => setRaw(e.target.value)}
              style={{ width: '100%', marginTop: 6 }}
            />
            <div style={{ marginTop: 6, opacity: .8 }}>{trackings.length} tracking(s)</div>
          </div>

          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            <button onClick={onAplicar} disabled={loading || trackings.length === 0}>
              {loading ? 'Aplicando…' : 'Aplicar estado'}
            </button>
            <button onClick={() => { setRaw(''); setLog([]) }} disabled={loading}>
              Limpiar
            </button>
          </div>
        </div>

        <div style={{ border: '1px solid #163E7A', borderRadius: 8, padding: 12, maxHeight: '60vh', overflow: 'auto' }}>
          <strong>Log</strong>
          <ul>{log.map((r, i) => <li key={i}>{r}</li>)}</ul>
        </div>
      </div>
    </div>
  )
}
