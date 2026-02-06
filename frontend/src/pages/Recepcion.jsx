import { useEffect, useState } from 'react'
import { api, toastOk, toastErr } from '../api'

const CR_TZ = 'America/Costa_Rica'

// Fecha hoy en CR como YYYY-MM-DD
function todayCR() {
  return new Intl.DateTimeFormat('en-CA', { timeZone: CR_TZ }).format(new Date())
}

// Convierte YYYY-MM-DD (local CR) a un ISO estable (usa 12:00 CR para evitar problemas de zona)
function ymdToCRNoonISO(ymd) {
  // CR no usa DST; -06:00 es fijo
  // Este ISO será convertido a UTC por Date().toISOString(), pero conserva el día correcto base CR.
  const d = new Date(`${ymd}T12:00:00-06:00`)
  return d.toISOString()
}

export default function Recepcion() {
  const [fecha, setFecha] = useState(todayCR())   // <-- NUEVO: fecha seleccionada
  const [marchamo, setMarchamo] = useState('')
  const [ubic, setUbic] = useState('')
  const [ubicOptions, setUbicOptions] = useState([])
  const [trackingBulk, setTrackingBulk] = useState('')
  const [log, setLog] = useState([])
  const [loading, setLoading] = useState(false)
  const [creatingSaco, setCreatingSaco] = useState(false)

  // Helper: hora local Costa Rica para logs
  const fmtCRTime = (v) => {
    const d = (typeof v === 'string' || typeof v === 'number') ? new Date(v) : (v || new Date())
    return d.toLocaleTimeString('es-CR', {
      timeZone: CR_TZ,
      hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit'
    })
  }

  // Carga de ubicaciones (solo MUEBLE) desde la BD
  useEffect(() => {
    (async () => {
      try {
        const { data } = await api.get('/ubicaciones/codigos', { params: { tipo: 'MUEBLE' } })
        setUbicOptions(Array.isArray(data) ? data : [])
      } catch (e) {
        toastErr(e)
      }
    })()
  }, [])

  const appendLog = (msg) => setLog(prev => [`[${fmtCRTime()}] ${msg}`, ...prev].slice(0, 200))

  const parseTrackings = (txt) => {
    if (!txt) return []
    // separa por coma, espacios o saltos de línea
    return txt
      .split(/[,\s]+/g)
      .map(s => s.trim())
      .filter(Boolean)
  }

  const crearSaco = async () => {
    if (!marchamo) {
      return toastOk('Debes indicar el número de marchamo para crear el saco.')
    }
    setCreatingSaco(true)
    try {
      const payload = {
        marchamo,
        fecha,                         // <-- NUEVO
        receivedAt: ymdToCRNoonISO(fecha) // <-- NUEVO
      }
      if (ubic) payload.ubicacionCodigo = ubic
      await api.post('/sacos', payload)
      appendLog(`Saco ${marchamo} creado${ubic ? ` con ubicación ${ubic}` : ''} (fecha ${fecha}).`)
      toastOk('Saco creado.')
    } catch (e) {
      toastErr(e)
    } finally {
      setCreatingSaco(false)
    }
  }

  // Pre-registro en lote: usa mismo marchamo y ubicación para todos (para paquetes sí es requerida la ubicación)
  const preregistrar = async () => {
    const list = parseTrackings(trackingBulk)
    if (!list.length) return toastOk('No hay números de envío para procesar.')
    if (!marchamo || !ubic) return toastOk('Indica marchamo y ubicación para registrar paquetes.')

    setLoading(true)
    let ok = 0, fail = 0
    for (const t of list) {
      try {
        await api.post('/paquetes', {
          tracking: t,
          marchamo,
          ubicacionCodigo: ubic,
          fecha,                         // <-- NUEVO
          receivedAt: ymdToCRNoonISO(fecha) // <-- NUEVO
        })
        ok++
        appendLog(`✔ Registrado ${t} → ${marchamo} @ ${ubic} (fecha ${fecha})`)
      } catch (e) {
        fail++
        const msg = (e?.response?.data?.message) || e?.message || 'Error'
        appendLog(`✖ Error ${t}: ${msg}`)
      }
    }
    setLoading(false)
    toastOk(`Listo. Registrados: ${ok}. Fallidos: ${fail}.`)
  }

  // Eliminar un paquete por tracking (maneja 404)
  const eliminarPaquete = async (t) => {
    const track = (t || '').trim()
    if (!track) return toastOk('Indica un número de envío para eliminar.')
    try {
      await api.delete(`/paquetes/${encodeURIComponent(track)}`)
      appendLog(`🗑 Paquete ${track} eliminado.`)
      toastOk('Paquete eliminado.')
    } catch (e) {
      const status = e?.response?.status
      if (status === 404) {
        appendLog(`⚠ No existe paquete ${track}`)
        toastOk('El número de envío no existe.')
      } else {
        toastErr(e)
      }
    }
  }

  // Eliminar marchamo: solo si NO tiene paquetes asociados
  const eliminarMarchamo = async () => {
    const code = (marchamo || '').trim()
    if (!code) return toastOk('Indica el marchamo a eliminar.')
    try {
      const { data } = await api.get(`/busqueda/marchamo/${encodeURIComponent(code)}`)
      const count = Array.isArray(data) ? data.length : 0
      if (count > 0) {
        return toastOk(`⚠ No se puede borrar. El marchamo ${code} tiene ${count} paquete(s).`)
      }
      await api.delete(`/sacos/${encodeURIComponent(code)}`)
      appendLog(`🗑 Saco ${code} eliminado.`)
      toastOk('Marchamo eliminado.')
    } catch (e) {
      toastErr(e)
    }
  }

  // Atajo: Enter dispara registro en el textarea si se usa con Ctrl/Shift/⌘
  const onBulkKeyDown = (e) => {
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey || e.shiftKey)) {
      e.preventDefault()
      preregistrar()
    }
  }

  return (
    <div className="page">
      <h2 style={{ marginBottom: 12 }}>Recepción</h2>

      {/* Fila 0: Fecha de recepción */}
      <div style={{ display:'grid', gap:10, gridTemplateColumns:'1fr', alignItems:'center', marginBottom:6 }}>
        <label style={{ display:'flex', gap:8, alignItems:'center' }}>
          <span>Fecha de recepción:</span>
          <input
            type="date"
            value={fecha}
            onChange={e => setFecha(e.target.value)}
            style={{ padding: 8, width: '260px' }}
          />
        </label>
        <div style={{ fontSize:12, opacity:.75, marginTop:-4 }}>
          * Por defecto es la fecha actual (zona horaria: Costa Rica). Si cambiás la fecha, se guardará con ese día.
        </div>
      </div>

      {/* Fila 1: Marchamo + botón Crear saco (solo requiere marchamo) */}
      <div style={{ display: 'grid', gap: 10, gridTemplateColumns: '1fr auto', alignItems: 'center', marginBottom: 10 }}>
        <label style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <span>Marchamo:</span>
          <input
            value={marchamo}
            onChange={e => setMarchamo(e.target.value.toUpperCase())}
            placeholder="HZCR-0000"
            style={{ padding: 8, width: '100%' }}
          />
        </label>
        <button onClick={crearSaco} disabled={creatingSaco}>
          {creatingSaco ? 'Creando…' : 'Crear saco'}
        </button>
      </div>

      {/* Fila 2: Ubicación (opcional para el saco, requerida para registrar paquetes) */}
      <div style={{ display: 'grid', gap: 10, gridTemplateColumns: '1fr', alignItems: 'center' }}>
        <label style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <span>Ubicación:</span>
          <select value={ubic} onChange={e => setUbic(e.target.value)} style={{ padding: 8, width: '100%' }}>
            <option value="">Seleccione…</option>
            {ubicOptions.map(c => <option key={c} value={c}>{c}</option>)}
          </select>
        </label>
      </div>

      {/* Escaneo múltiple */}
      <div style={{ marginTop: 16 }}>
        <label style={{ display: 'block', marginBottom: 6 }}>
          Números de envío (separados por coma, espacio o salto de línea):
        </label>
        <textarea
          value={trackingBulk}
          onChange={e => setTrackingBulk(e.target.value)}
          onKeyDown={onBulkKeyDown}
          placeholder={`HZCR12345, HZCR67890 HZCR54321
...`}
          rows={6}
          style={{ width: '100%', padding: 12, fontFamily: 'monospace', fontSize: 14, resize: 'vertical', minHeight: 140 }}
        />
        <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
          <button onClick={preregistrar} disabled={loading || !marchamo || !ubic || !parseTrackings(trackingBulk).length}>
            {loading ? 'Agregando…' : 'Agregar paquetes'}
          </button>
          <button onClick={() => setTrackingBulk('')} disabled={loading}>
            Limpiar
          </button>
        </div>
        <div style={{ opacity: 0.7, marginTop: 4 }}>
          {parseTrackings(trackingBulk).length} por registrar
        </div>
      </div>

      {/* Borrado */}
      <div style={{ marginTop: 24 }}>
        <h3>Eliminar</h3>
        <div style={{ display: 'grid', gap: 10, gridTemplateColumns: 'repeat(auto-fit, minmax(300px, 1fr))', alignItems: 'center' }}>
          <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            <input
              placeholder="Número de envío a eliminar (tracking)"
              onKeyDown={(e) => {
                if (e.key === 'Enter') eliminarPaquete(e.currentTarget.value)
              }}
              style={{ padding: 8, width: '100%' }}
            />
            <button onClick={(e) => {
              const inp = e.currentTarget.previousSibling
              eliminarPaquete(inp?.value || '')
              if (inp) inp.value = ''
            }}>
              Eliminar paquete
            </button>
          </div>

          <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            <input
              placeholder="Marchamo a eliminar"
              value={marchamo}
              onChange={e => setMarchamo(e.target.value.toUpperCase())}
              style={{ padding: 8, width: '100%' }}
            />
            <button onClick={eliminarMarchamo}>
              Eliminar marchamo
            </button>
          </div>
        </div>
        <div style={{ opacity: .7, marginTop: 6 }}>
          * Si el marchamo tiene paquetes asociados, no se puede eliminar.
        </div>
      </div>

      {/* Log */}
      <div style={{ marginTop: 16 }}>
        <strong>Log</strong>
        <ul>{log.map((r, i) => <li key={i}>{r}</li>)}</ul>
      </div>
    </div>
  )
}
