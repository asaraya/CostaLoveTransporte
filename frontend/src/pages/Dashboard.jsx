import { useEffect, useState } from 'react'
import { api } from '../api'

const CR_TZ = 'America/Costa_Rica'

// YYYY-MM-DD -> ISO con offset CR fijo (-06:00)
const toCRISO = (yyyyMmDd, hh = '00', mm = '00', ss = '00') => {
  if (!yyyyMmDd) return null
  return `${yyyyMmDd}T${hh}:${mm}:${ss}-06:00`
}

export default function Dashboard() {
  const [fecha, setFecha] = useState(() =>
    new Intl.DateTimeFormat('en-CA', { timeZone: CR_TZ }).format(new Date())
  )

  const [summary, setSummary] = useState(null)
  const [topUbic, setTopUbic] = useState([])
  const [ultimosRec, setUltimosRec] = useState([])
  const [ultimosMov, setUltimosMov] = useState([])
  const [loading, setLoading] = useState(false)

  // Modal
  const [ubicModal, setUbicModal] = useState({
    open: false,
    ubicacion: '',
    rows: [],
    loading: false,
    error: null,
  })

  // Ver paquetes por fecha (por recepción REAL)
  const [fechaPF, setFechaPF] = useState(() =>
    new Intl.DateTimeFormat('en-CA', { timeZone: CR_TZ }).format(new Date())
  )
  const [tabPF, setTabPF] = useState('RECIBIDOS') // RECIBIDOS | ENTREGADOS | INVENTARIO
  const [pfData, setPfData] = useState({ recibidos: [], entregados: [], inventario: [] })
  const [pfLoading, setPfLoading] = useState(false)

  // Matriz mensual
  const [mesResumen, setMesResumen] = useState(() => {
    const d = new Date()
    const y = d.getFullYear()
    const m = String(d.getMonth() + 1).padStart(2, '0')
    return `${y}-${m}`
  })
  const [matrizMes, setMatrizMes] = useState([])
  const [loadingMes, setLoadingMes] = useState(false)

  const cargarTopUbicaciones = async () => {
    try {
      const { data } = await api.get('/dashboard/top-ubicaciones', { params: { limit: 100000 } })
      const ubicAll = Array.isArray(data) ? [...data] : []
      ubicAll.sort((a, b) => (b?.cantidad ?? 0) - (a?.cantidad ?? 0))
      setTopUbic(ubicAll)
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
        api.get('/dashboard/top-ubicaciones', { params: { limit: 100000 } }),
        api.get('/dashboard/ultimos-recibidos', { params: { limit: 10, fecha } }),
        api.get('/dashboard/ultimos-movimientos', { params: { fecha, limit: 100000 } }),
      ])

      setSummary(s.data)

      const ubicAll = Array.isArray(u.data) ? [...u.data] : []
      ubicAll.sort((a, b) => (b?.cantidad ?? 0) - (a?.cantidad ?? 0))
      setTopUbic(ubicAll)

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

  useEffect(() => { cargarTopUbicaciones() }, [])
  useEffect(() => { cargarFecha() }, [fecha])

  // Modal: ahora asumimos que “ubicacion” representa el distrito (según backend nuevo)
  const openUbicModal = async (ubicacion) => {
    setUbicModal({ open: true, ubicacion, rows: [], loading: true, error: null })
    try {
      const { data } = await api.get(`/busqueda/distrito/${encodeURIComponent(ubicacion)}`, {
        params: { estado: 'NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE' }
      })
      const arr = Array.isArray(data) ? data : []
      const rows = arr
        .filter(r => String(r?.estado ?? '').toUpperCase() === 'NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE')
        .sort((a, b) => new Date(b?.received_at ?? 0).getTime() - new Date(a?.received_at ?? 0).getTime())

      setUbicModal(prev => ({ ...prev, rows, loading: false }))
    } catch (e) {
      setUbicModal(prev => ({
        ...prev,
        loading: false,
        error: e?.response?.data?.message || e?.message || 'Error cargando paquetes'
      }))
    }
  }

  const closeUbicModal = () => setUbicModal(prev => ({ ...prev, open: false }))

  useEffect(() => {
    const onKey = (e) => { if (e.key === 'Escape') closeUbicModal() }
    if (ubicModal.open) window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [ubicModal.open])

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

      const inventario = recibidos.filter(r =>
        String(r?.estado ?? '').toUpperCase() === 'NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE'
      )

      setPfData({ recibidos, entregados, inventario })
    } catch (e) {
      alert(e?.response?.data?.message || e?.message || 'Error')
    } finally {
      setPfLoading(false)
    }
  }

  const currentPFRows = (() => {
    if (tabPF === 'ENTREGADOS') return pfData.entregados
    if (tabPF === 'INVENTARIO') return pfData.inventario
    return pfData.recibidos
  })()

  const currentPFDateKey = 'received_at'

  // Matriz mensual usando /reportes/diario?flat=true (backend nuevo: fuera_de_ruta/vencidos/dos_intentos)
  const cargarMatrizMes = async () => {
    if (!mesResumen) return
    setLoadingMes(true)
    try {
      const [yStr, mStr] = mesResumen.split('-')
      const year = parseInt(yStr, 10)
      const month = parseInt(mStr, 10)
      if (!year || !month) { setMatrizMes([]); return }

      const daysInMonth = new Date(year, month, 0).getDate()

      const requests = []
      for (let day = 1; day <= daysInMonth; day++) {
        const dStr = String(day).padStart(2, '0')
        const fechaDia = `${yStr}-${mStr}-${dStr}`

        requests.push(
          api.get('/reportes/diario', { params: { fecha: fechaDia, flat: true } })
            .then(res => ({ fecha: fechaDia, raw: res.data }))
            .catch(() => ({ fecha: fechaDia, raw: null }))
        )
      }

      const results = await Promise.all(requests)

      const normalize = (raw) => {
        if (!raw) return {}

        const unwrapRow = (value) => {
          if (!value) return null
          if (Array.isArray(value)) return value[0] ?? null
          if (typeof value === 'object') {
            const rsKey = Object.keys(value).find(k => /^#result-set-\d+$/i.test(k))
            if (rsKey && Array.isArray(value[rsKey])) return value[rsKey][0] ?? null
          }
          return value
        }

        const data = unwrapRow(raw) || {}

        const toNumber = (v) => {
          if (v === null || v === undefined || v === '') return null
          const n = Number(v)
          return Number.isFinite(n) ? n : v
        }

        const getField = (...keys) => {
          for (const k of keys) {
            if (Object.prototype.hasOwnProperty.call(data, k) && data[k] != null) return toNumber(data[k])
          }
          return null
        }

        return {
          inventario:     getField('inventario', 'INVENTARIO'),
          recibido:       getField('recibido', 'RECIBIDO', 'recibidos'),
          entregado:      getField('entregado', 'ENTREGADO', 'entregados'),
          no_entregable:  getField('no_entregable', 'NO_ENTREGABLE'),
          fuera_de_ruta:  getField('fuera_de_ruta', 'FUERA_DE_RUTA'),
          vencidos:       getField('vencidos', 'VENCIDOS'),
          dos_intentos:   getField('dos_intentos', 'DOS_INTENTOS'),
          total:          getField('total', 'TOTAL'),
        }
      }

      const rows = results.map(({ fecha, raw }) => ({ fecha, ...normalize(raw) }))
      setMatrizMes(rows)
    } catch (e) {
      alert(e?.response?.data?.message || e?.message || 'Error cargando matriz mensual')
      setMatrizMes([])
    } finally {
      setLoadingMes(false)
    }
  }

  useEffect(() => { cargarMatrizMes() }, [mesResumen])

  const fmtCell = (v) => (v === null || v === undefined || v === '' ? '-' : v)

  return (
    <div>
      <h3>Dashboard</h3>

      <div style={{ display: 'flex', gap: 12, alignItems: 'center', marginBottom: 12 }}>
        <label>Fecha:
          <input type="date" value={fecha} onChange={e => setFecha(e.target.value)} />
        </label>
        <button onClick={cargarTodo} disabled={loading}>{loading ? 'Actualizando…' : 'Actualizar'}</button>
      </div>

      {summary && (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 12, marginBottom: 12 }}>
          <Kpi title="Paquetes totales" value={summary.totales?.paquetes ?? summary.totalPaquetes ?? 0} />
          <Kpi title="Inventario actual" value={summary.inventarioActual ?? 0} />
        </div>
      )}

      {summary && (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 12, marginBottom: 12 }}>
          <Kpi title={`Recibidos ${summary.fecha ?? fecha}`} value={summary.hoy?.recibidos ?? 0} />
          <Kpi title={`Entregados ${summary.fecha ?? fecha}`} value={summary.hoy?.entregados ?? 0} />
          <Kpi title={`No entregables ${summary.fecha ?? fecha}`} value={summary.hoy?.noEntregable ?? summary.hoy?.no_entregable ?? summary.hoy?.devoluciones ?? 0} />
        </div>
      )}

      {summary && (
        <div style={{ marginBottom: 16 }}>
          <h4>Paquetes por estado</h4>
          <table border="1" cellPadding="6" width="100%">
            <thead><tr><th>Estado</th><th>Cantidad</th></tr></thead>
            <tbody>
              {summary.byEstado?.map((r, i) => (
                <tr key={i}><td>{r.estado}</td><td>{r.cantidad}</td></tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
        <div>
          <h4>Distritos con inventario (de mayor a menor)</h4>
          <table border="1" cellPadding="6" width="100%">
            <thead><tr><th>Distrito</th><th>Cantidad</th></tr></thead>
            <tbody>
              {topUbic.map((r, i) => (
                <tr key={i}>
                  <td>
                    <button
                      onClick={() => openUbicModal(r.ubicacion)}
                      style={{ background: 'none', border: 'none', color: '#0b66c3', textDecoration: 'underline', padding: 0, cursor: 'pointer' }}
                      title="Ver paquetes en este distrito"
                    >
                      {r.ubicacion}
                    </button>
                  </td>
                  <td>{r.cantidad}</td>
                </tr>
              ))}
              {!topUbic.length && (
                <tr><td colSpan={2} style={{ textAlign: 'center', opacity: .7 }}>Sin datos</td></tr>
              )}
            </tbody>
          </table>
        </div>

        <div>
          <h4>Últimos recibidos (2 fechas)</h4>
          <table border="1" cellPadding="6" width="100%">
            <thead>
              <tr>
                <th>Tracking</th>
                <th>Marchamo</th>
                <th>Distrito</th>
                <th>Recibido (real)</th>
                <th>Entrada inventario</th>
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
                  <td>{fmtDT(r.entrada_inventario_at ?? r.changed_at ?? r.changedAt)}</td>
                  <td>{r.estado}</td>
                </tr>
              ))}
              {!ultimosRec.length && (
                <tr><td colSpan={6} style={{ textAlign: 'center', opacity: .7 }}>Sin datos</td></tr>
              )}
            </tbody>
          </table>
        </div>
      </div>

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
            <button onClick={() => setTabPF('ENTREGADOS')} style={tabBtnStyle(tabPF === 'ENTREGADOS')}>
              Entregados ({pfData.entregados.length})
            </button>
            <button onClick={() => setTabPF('INVENTARIO')} style={tabBtnStyle(tabPF === 'INVENTARIO')}>
              En inventario ({pfData.inventario.length})
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
                    <td style={td}>{r.estado ?? '-'}</td>
                    <td style={td}>{fmtDT(r[currentPFDateKey])}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </div>

      <div style={{ marginTop: 16 }}>
        <h4>Resumen mensual (matriz tipo hoja)</h4>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 8, flexWrap: 'wrap' }}>
          <label>Mes:
            <input type="month" value={mesResumen} onChange={e => setMesResumen(e.target.value)} style={{ marginLeft: 4 }} />
          </label>
          <button onClick={cargarMatrizMes} disabled={loadingMes || !mesResumen}>
            {loadingMes ? 'Cargando…' : 'Ver mes'}
          </button>
        </div>

        <div style={{ border: '1px solid rgba(22,62,122,.15)', borderRadius: 8, padding: 8 }}>
          <div style={{ maxHeight: 400, overflowY: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr>
                  <th style={th}>Fecha</th>
                  <th style={th}>Inventario</th>
                  <th style={th}>Recibido</th>
                  <th style={th}>Entregado</th>
                  <th style={th}>No entregable</th>
                  <th style={th}>Fuera de ruta</th>
                  <th style={th}>Vencidos</th>
                  <th style={th}>Dos intentos</th>
                  <th style={th}>Total</th>
                </tr>
              </thead>
              <tbody>
                {matrizMes.length === 0 ? (
                  <tr>
                    <td colSpan={9} style={{ padding: 12, textAlign: 'center', opacity: .7 }}>
                      Sin datos para el mes seleccionado
                    </td>
                  </tr>
                ) : matrizMes.map((r) => (
                  <tr key={r.fecha} style={{ borderBottom: '1px solid rgba(0,0,0,0.06)' }}>
                    <td style={td}>{r.fecha}</td>
                    <td style={td}>{fmtCell(r.inventario)}</td>
                    <td style={td}>{fmtCell(r.recibido)}</td>
                    <td style={td}>{fmtCell(r.entregado)}</td>
                    <td style={td}>{fmtCell(r.no_entregable)}</td>
                    <td style={td}>{fmtCell(r.fuera_de_ruta)}</td>
                    <td style={td}>{fmtCell(r.vencidos)}</td>
                    <td style={td}>{fmtCell(r.dos_intentos)}</td>
                    <td style={td}>{fmtCell(r.total)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </div>

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
                  <td>{r.estado_from ?? '-'}</td>
                  <td>{r.estado_to ?? '-'}</td>
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

      {ubicModal.open && (
        <div
          onClick={closeUbicModal}
          style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.35)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1000 }}
        >
          <div onClick={e => e.stopPropagation()} style={blueCard}>
            <button onClick={closeUbicModal} aria-label="Cerrar" title="Cerrar" style={closeBtn}>×</button>

            <h4 style={{ margin: '0 0 10px', color: '#fff' }}>
              Paquetes en: <span style={{ fontWeight: 800 }}>{ubicModal.ubicacion}</span>
              <span style={pill}>INVENTARIO</span>
            </h4>

            {ubicModal.loading && <div style={{ padding: 8, color: '#e8f0ff' }}>Cargando paquetes…</div>}
            {ubicModal.error && <div style={{ padding: 8, color: '#ffdde0' }}>{ubicModal.error}</div>}

            {!ubicModal.loading && !ubicModal.error && (
              <>
                <div style={{ marginBottom: 8, opacity: .9, color: '#e8f0ff' }}>
                  Total (inventario): {ubicModal.rows.length}
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
                      {ubicModal.rows.length ? ubicModal.rows.map((r, idx) => (
                        <tr key={r.id ?? r.tracking_code ?? idx} style={{ borderBottom: '1px solid rgba(0,0,0,0.06)' }}>
                          <td style={td}>{r.tracking_code}</td>
                          <td style={td}>{r.marchamo}</td>
                          <td style={td}>{r.distrito_nombre ?? '-'}</td>
                          <td style={td}>{r.estado}</td>
                          <td style={td}>{fmtDT(r.received_at)}</td>
                        </tr>
                      )) : (
                        <tr><td colSpan={5} style={{ padding: 12, textAlign: 'center', opacity: .7 }}>Sin paquetes en inventario</td></tr>
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
