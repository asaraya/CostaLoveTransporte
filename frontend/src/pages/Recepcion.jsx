import { useEffect, useMemo, useState } from 'react'
import { api, toastErr, toastOk } from '../api'

const CR_TZ = 'America/Costa_Rica'

// Distritos requeridos (fallback por si no existe endpoint en backend)
const FALLBACK_DISTRICTS = ['La colonia', 'Jimenez', 'Colorado', 'La Rita', 'Roxana']

function todayCR() {
  return new Intl.DateTimeFormat('en-CA', { timeZone: CR_TZ }).format(new Date())
}

function ymdToCRNoonISO(ymd) {
  const d = new Date(`${ymd}T12:00:00-06:00`)
  return d.toISOString()
}

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

export default function Recepcion() {
  const [fecha, setFecha] = useState(todayCR())
  const [marchamo, setMarchamo] = useState('')
  const [rawTrackings, setRawTrackings] = useState('')

  // Distrito (reemplaza Ubicación)
  const [distritoNombre, setDistritoNombre] = useState('')
  const [distritos, setDistritos] = useState(FALLBACK_DISTRICTS)

  const trackings = useMemo(() => parseTokens(rawTrackings), [rawTrackings])

  const [loadingSaco, setLoadingSaco] = useState(false)
  const [loadingPre, setLoadingPre] = useState(false)
  const [log, setLog] = useState([])
  const appendLog = (line) => setLog(prev => [...prev, line])

  // Intentar cargar distritos desde backend (si existe endpoint).
  // Si falla, se mantiene el fallback fijo.
  useEffect(() => {
    let alive = true
    ;(async () => {
      try {
        // 🔧 Endpoint sugerido: GET /api/distritos (ajustá si tu backend usa otro)
        const { data } = await api.get('/distritos')
        let list = []

        if (Array.isArray(data)) {
          // puede venir ["La colonia", ...] o [{nombre:"..."}, ...]
          list = data
            .map(x => (typeof x === 'string' ? x : x?.nombre))
            .filter(Boolean)
        }

        if (alive && list.length) {
          setDistritos(list)
          // si no hay seleccionado, setear primero
          if (!distritoNombre) setDistritoNombre(list[0])
        }
      } catch {
        // fallback
        if (alive) {
          setDistritos(FALLBACK_DISTRICTS)
          if (!distritoNombre) setDistritoNombre(FALLBACK_DISTRICTS[0])
        }
      }
    })()
    return () => { alive = false }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const onCrearSaco = async () => {
    try {
      const m = (marchamo || '').trim()
      if (!m) {
        toastErr({ message: 'Marchamo requerido' })
        return
      }

      setLoadingSaco(true)
      setLog([])

      // ✅ Nuevo contrato: { marchamo, defaultDistritoNombre? }
      const payload = {
        marchamo: m,
        defaultDistritoNombre: (distritoNombre || '').trim() || undefined,
      }

      const { data } = await api.post('/sacos', payload)
      toastOk(`Saco creado/confirmado: ${data?.marchamo || m}`)
      appendLog(`✅ Saco listo: ${data?.marchamo || m}`)
    } catch (e) {
      toastErr(e)
    } finally {
      setLoadingSaco(false)
    }
  }

  const onEliminarSaco = async () => {
    try {
      const m = (marchamo || '').trim()
      if (!m) {
        toastErr({ message: 'Marchamo requerido' })
        return
      }
      if (!confirm(`¿Eliminar saco ${m}? (solo si está vacío)`)) return

      await api.delete(`/sacos/${encodeURIComponent(m)}`)
      toastOk(`Saco eliminado: ${m}`)
      appendLog(`🗑️ Saco eliminado: ${m}`)
    } catch (e) {
      toastErr(e)
    }
  }

  const onPreregistrar = async () => {
    try {
      const m = (marchamo || '').trim()
      const d = (distritoNombre || '').trim()

      if (!m) {
        toastErr({ message: 'Marchamo requerido' })
        return
      }
      if (!d) {
        toastErr({ message: 'Distrito requerido' })
        return
      }
      if (!trackings.length) {
        toastErr({ message: 'Ingresá al menos un tracking válido (HZCR/CR + dígitos)' })
        return
      }

      setLoadingPre(true)
      setLog([])

      const receivedAt = ymdToCRNoonISO(fecha)

      let ok = 0
      let fail = 0

      for (const t of trackings) {
        try {
          // ✅ Nuevo contrato: { tracking, marchamo, distritoNombre, receivedAt }
          // (trackingCode se puede omitir; uso tracking por claridad)
          // eslint-disable-next-line no-await-in-loop
          const { data } = await api.post('/paquetes', {
            tracking: t,
            marchamo: m,
            distritoNombre: d,
            receivedAt,
          })
          ok++
          appendLog(`✅ ${t} preregistrado (Distrito: ${d}) → id ${data?.paquete_id ?? data?.id ?? ''}`)
        } catch (e) {
          fail++
          appendLog(`❌ ${t}: ${e?.response?.data?.message || e.message}`)
        }
      }

      toastOk(`Preregistro: ${ok} ok, ${fail} error(es)`)
    } catch (e) {
      toastErr(e)
    } finally {
      setLoadingPre(false)
    }
  }

  return (
    <div className="page">
      <h2 style={{ marginBottom: 12 }}>Recepción</h2>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
        {/* Columna izquierda */}
        <div style={{ border: '1px solid #163E7A', borderRadius: 8, padding: 12 }}>
          <div style={{ display: 'grid', gap: 10 }}>
            <label style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
              <span>Fecha:</span>
              <input
                type="date"
                value={fecha}
                onChange={(e) => setFecha(e.target.value)}
                style={{ padding: 8, width: 220 }}
              />
            </label>

            <label style={{ display: 'grid', gap: 6 }}>
              <span>Marchamo:</span>
              <input
                value={marchamo}
                onChange={(e) => setMarchamo(e.target.value)}
                placeholder="Ej: MCH-001"
              />
            </label>

            <label style={{ display: 'grid', gap: 6 }}>
              <span>Distrito:</span>
              <select
                value={distritoNombre}
                onChange={(e) => setDistritoNombre(e.target.value)}
              >
                {distritos.map((x) => (
                  <option key={x} value={x}>{x}</option>
                ))}
              </select>
              <div style={{ fontSize: 12, opacity: .75 }}>
                * Cada paquete queda ligado a un Distrito (reemplaza el concepto de “Mueble/Ubicación”).
              </div>
            </label>

            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
              <button onClick={onCrearSaco} disabled={loadingSaco}>
                {loadingSaco ? 'Creando…' : 'Crear/Confirmar saco'}
              </button>
              <button onClick={onEliminarSaco} disabled={loadingSaco}>
                Eliminar saco (vacío)
              </button>
            </div>
          </div>

          <hr style={{ margin: '16px 0', opacity: .25 }} />

          <div style={{ display: 'grid', gap: 8 }}>
            <label>Trackings (coma/espacio/salto de línea):</label>
            <textarea
              rows={8}
              placeholder="HZCR12345 CR98765 ..."
              value={rawTrackings}
              onChange={(e) => setRawTrackings(e.target.value)}
              style={{ width: '100%' }}
            />
            <div style={{ opacity: .8 }}>{trackings.length} tracking(s) válido(s)</div>

            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
              <button onClick={onPreregistrar} disabled={loadingPre || trackings.length === 0}>
                {loadingPre ? 'Preregistrando…' : 'Preregistrar paquetes'}
              </button>
              <button onClick={() => { setRawTrackings(''); setLog([]) }} disabled={loadingPre}>
                Limpiar
              </button>
            </div>
          </div>
        </div>

        {/* Columna derecha */}
        <div style={{ border: '1px solid #163E7A', borderRadius: 8, padding: 12, maxHeight: '70vh', overflow: 'auto' }}>
          <strong>Log</strong>
          <ul>{log.map((r, i) => <li key={i}>{r}</li>)}</ul>
        </div>
      </div>
    </div>
  )
}
