import { useEffect, useState } from 'react'
import { api } from '../api'

const CR_TZ = 'America/Costa_Rica'

// Igual que en Reportes: fecha YYYY-MM-DD → ISO con offset local
const toOffsetISO = (yyyyMmDd, hh = '00', mm = '00', ss = '00') => {
  if (!yyyyMmDd) return null
  const offMin = -new Date().getTimezoneOffset()
  const sign = offMin >= 0 ? '+' : '-'
  const abs = Math.abs(offMin)
  const hhOff = String(Math.floor(abs / 60)).padStart(2, '0')
  const mmOff = String(abs % 60).padStart(2, '0')
  return `${yyyyMmDd}T${hh}:${mm}:${ss}${sign}${hhOff}:${mmOff}`
}

export default function Dashboard(){
  const [fecha, setFecha] = useState(() =>
    new Intl.DateTimeFormat('en-CA', { timeZone: CR_TZ }).format(new Date())
  )
  const [summary, setSummary] = useState(null)
  const [topUbic, setTopUbic] = useState([])
  const [ultimosRec, setUltimosRec] = useState([])
  const [ultimosMov, setUltimosMov] = useState([])
  const [loading, setLoading] = useState(false)

  // Modal de paquetes por mueble/ubicación
  const [ubicModal, setUbicModal] = useState({
    open: false,
    ubicacion: '',
    rows: [],
    loading: false,
    error: null,
  })

  // === Sección: Ver paquetes por fecha (por fecha REAL de recepción) ===
  const [fechaPF, setFechaPF] = useState(() =>
    new Intl.DateTimeFormat('en-CA', { timeZone: CR_TZ }).format(new Date())
  )
  const [tabPF, setTabPF] = useState('RECIBIDOS') // RECIBIDOS | ENTREGADOS | INVENTARIO
  const [pfData, setPfData] = useState({
    recibidos: [],
    entregados: [],
    inventario: [],
  })
  const [pfLoading, setPfLoading] = useState(false)

  // === Nueva sección: Matriz mensual (tipo hoja) ===
  const [mesResumen, setMesResumen] = useState(() => {
    const d = new Date()
    const y = d.getFullYear()
    const m = String(d.getMonth() + 1).padStart(2, '0')
    return `${y}-${m}`           // formato YYYY-MM
  })
  const [matrizMes, setMatrizMes] = useState([]) // [{fecha, inventario, recibido, entregado, enrutes, otras_zonas, vencidos, no_entregar, transporte, total}]
  const [loadingMes, setLoadingMes] = useState(false)

  const sameLocalDateCR = (dt, ymd) => {
    try {
      const d = new Date(dt)
      const str = new Intl.DateTimeFormat('en-CA', { timeZone: CR_TZ }).format(d)
      return str === ymd
    } catch { return false }
  }

  const cargarTopUbicaciones = async () => {
    try {
      const { data } = await api.get('/dashboard/top-ubicaciones', { params: { limit: 100000 } })
      const ubicAll = Array.isArray(data) ? [...data] : []
      ubicAll.sort((a,b) => (b?.cantidad ?? 0) - (a?.cantidad ?? 0))
      setTopUbic(ubicAll)
    } catch (e) {
      const msg = e?.response?.data?.message || e?.message || 'Error'
      alert(msg)
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
      // Ordenamos por la fecha lógica (real) de movimiento, descendente
      const movHoy = movSrc.sort((a,b) => {
        const da = new Date(movFechaOficial(a) ?? 0).getTime()
        const db = new Date(movFechaOficial(b) ?? 0).getTime()
        return db - da
      })
      setUltimosMov(movHoy)
    } catch (e) {
      const msg = e?.response?.data?.message || e?.message || 'Error'
      alert(msg)
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
      ubicAll.sort((a,b) => (b?.cantidad ?? 0) - (a?.cantidad ?? 0))
      setTopUbic(ubicAll)

      setUltimosRec(Array.isArray(r.data) ? r.data : [])

      const movSrc = Array.isArray(m.data) ? m.data : []
      // Ordenamos por la fecha lógica (real) de movimiento, descendente
      const movHoy = movSrc.sort((a,b) => {
        const da = new Date(movFechaOficial(a) ?? 0).getTime()
        const db = new Date(movFechaOficial(b) ?? 0).getTime()
        return db - da
      })
      setUltimosMov(movHoy)
    } catch (e) {
      const msg = e?.response?.data?.message || e?.message || 'Error'
      alert(msg)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { cargarTopUbicaciones() }, [])     // carga única
  useEffect(() => { cargarFecha() }, [fecha])         // al cambiar fecha (incluye primera carga)

  // === Modal handlers ===
  const openUbicModal = async (ubicacion) => {
    setUbicModal({ open: true, ubicacion, rows: [], loading: true, error: null })
    try {
      // Trae paquetes de la ubicación y FILTRA solo INVENTARIO
      const { data } = await api.get(`/busqueda/ubicacion/${encodeURIComponent(ubicacion)}`)
      const arr = Array.isArray(data) ? data : []
      const rows = arr
        .filter((r) => String(r?.estado ?? '').toUpperCase() === 'EN_INVENTARIO')
        .sort((a,b) => {
          const da = new Date(a?.received_at ?? 0).getTime()
          const db = new Date(b?.received_at ?? 0).getTime()
          return db - da
        })
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

  // Cerrar con ESC
  useEffect(() => {
    const onKey = (e) => { if (e.key === 'Escape') closeUbicModal() }
    if (ubicModal.open) window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [ubicModal.open])

  // Fecha lógica del movimiento según el estado destino (fechas REALES si existen)
  function movFechaOficial(r){
    if (!r) return null
    if (r.estado_to === 'EN_INVENTARIO' && r.received_at)  return r.received_at
    if (r.estado_to === 'ENTREGADO'     && r.delivered_at) return r.delivered_at
    if ((r.estado_to === 'DEVOLUCION' || r.estado_to === 'EN_TRANSITO_A_TIENDAS_AEROPOST') && r.returned_at)  return r.returned_at
    return r.changed_at ?? r.changedAt ?? null
  }

  // === Ver paquetes por fecha (por fecha REAL de recepción) ===
  const cargarPorFecha = async () => {
    if (!fechaPF) return
    setPfLoading(true)
    try {
      const iniISO = toOffsetISO(fechaPF, '00', '00', '00')
      const finISO = toOffsetISO(fechaPF, '23', '59', '59')

      // Solo paquetes cuya FECHA REAL de RECEPCION cae ese día
      const { data } = await api.get('/busqueda/fecha', {
        params: { tipoFecha: 'RECEPCION', desde: iniISO, hasta: finISO }
      })

      const recibidos = Array.isArray(data) ? data : []

      // Entre los recibidos ese día: cuántos ya se entregaron y cuántos siguen en inventario
      const entregados = recibidos.filter(r =>
        String(r?.estado ?? '').toUpperCase() === 'ENTREGADO'
      )

      const inventario = recibidos.filter(r =>
        String(r?.estado ?? '').toUpperCase() === 'EN_INVENTARIO'
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

  // Para esta sección, siempre usamos la fecha REAL de recepción
  const currentPFDateKey = 'received_at'

  // === Matriz mensual (usa /reportes/diario con las cantidades por columna) ===
  const cargarMatrizMes = async () => {
    if (!mesResumen) return
    setLoadingMes(true)
    try {
      const [yStr, mStr] = mesResumen.split('-')
      const year = parseInt(yStr, 10)
      const month = parseInt(mStr, 10) // 1-12

      if (!year || !month) {
        setMatrizMes([])
        setLoadingMes(false)
        return
      }

      const daysInMonth = new Date(year, month, 0).getDate()

      const requests = []
      for (let day = 1; day <= daysInMonth; day++) {
        const dStr = String(day).padStart(2, '0')
        const fechaDia = `${yStr}-${mStr}-${dStr}`

        requests.push(
          api.get('/reportes/diario', { params: { fecha: fechaDia, flat: true} })
            .then(res => ({ fecha: fechaDia, raw: res.data }))
            .catch(() => ({ fecha: fechaDia, raw: null }))
        )
      }

      const results = await Promise.all(requests)

      const normalize = (raw) => {
        if (!raw) return {}

        // === FIX (Opción B): "unwrap" para SimpleJdbcCall ===
        // Soporta:
        // 1) raw = [ { ... } ]
        // 2) raw = { "#result-set-1": [ { ... } ], ... }
        // 3) raw = { ... } (ya plano)
        const unwrapRow = (value) => {
          if (!value) return null

          // Caso 1: array directo
          if (Array.isArray(value)) return value[0] ?? null

          // Caso 2: Map estilo SimpleJdbcCall
          if (typeof value === 'object') {
            const rsKey = Object.keys(value).find(k => /^#result-set-\d+$/i.test(k))
            if (rsKey && Array.isArray(value[rsKey])) return value[rsKey][0] ?? null

            // Fallbacks comunes (por si cambia el backend)
            if (Array.isArray(value.result)) return value.result[0] ?? null
            if (Array.isArray(value.rows)) return value.rows[0] ?? null
          }

          // Caso 3: ya viene plano
          return value
        }

        const data = unwrapRow(raw) || {}

        const toNumber = (value) => {
          if (value === null || value === undefined || value === '') return null
          const n = Number(value)
          return Number.isFinite(n) ? n : value
        }

        const getField = (...keys) => {
          for (const k of keys) {
            if (Object.prototype.hasOwnProperty.call(data, k) && data[k] != null) {
              return toNumber(data[k])
            }
          }
          return null
        }

        return {
          // inventario del día (generalmente inventario inicial)
          inventario:  getField('inventario', 'INVENTARIO', 'inv_inicial', 'inventario_inicial'),
          // paquetes recibidos ese día
          recibido:    getField('recibido', 'RECIBIDO', 'recibidos', 'RECIBIDOS'),
          // paquetes entregados ese día
          entregado:   getField('entregado', 'ENTREGADO', 'entregados', 'ENTREGADOS'),
          // devoluciones: enrute
          enrutes:     getField('enrutes', 'ENRUTES', 'dev_enrute', 'DEV_ENRUTE', 'devoluciones_enrute'),
          // devoluciones: otras zonas
          otras_zonas: getField('otras_zonas', 'OTRAS_ZONAS', 'dev_otras_zonas', 'DEV_OTRAS_ZONAS'),
          // devoluciones: vencidos
          vencidos:    getField('vencidos', 'VENCIDOS', 'dev_vencidos', 'DEV_VENCIDOS'),
          // devoluciones: no entregar
          no_entregar: getField('no_entregar', 'NO_ENTREGAR', 'dev_no_entregar', 'DEV_NO_ENTREGAR'),
          // devoluciones: transporte (NUEVO)
          transporte:  getField('transporte', 'TRANSPORTE', 'dev_transporte', 'DEV_TRANSPORTE'),
          // total (según el SP: inventario final / total del día)
          total:       getField('total', 'TOTAL', 'inv_final', 'inventario_final')
        }
      }

      const rows = results.map(({ fecha, raw }) => {
        const norm = normalize(raw)
        return { fecha, ...norm }
      })

      setMatrizMes(rows)
    } catch (e) {
      alert(e?.response?.data?.message || e?.message || 'Error cargando matriz mensual')
      setMatrizMes([])
    } finally {
      setLoadingMes(false)
    }
  }

  // Cargar matriz del mes actual al entrar y cuando cambie el mes
  useEffect(() => {
    cargarMatrizMes()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mesResumen])

  const fmtCell = (v) =>
    v === null || v === undefined || v === '' ? '-' : v

  return (
    <div>
      <h3>Dashboard</h3>

      <div style={{display:'flex', gap:12, alignItems:'center', marginBottom:12}}>
        <label>Fecha:
          <input type="date" value={fecha} onChange={e=>setFecha(e.target.value)} />
        </label>
        <button onClick={cargarTodo} disabled={loading}>{loading ? 'Actualizando…' : 'Actualizar'}</button>
      </div>

      {/* KPIs */}
      {summary && (
        <div style={{display:'grid', gridTemplateColumns:'repeat(4, 1fr)', gap:12, marginBottom:12}}>
          <Kpi title="Paquetes totales" value={summary.totales?.paquetes}/>
          <Kpi title="Inventario actual" value={summary.inventarioActual}/>
        </div>
      )}

      {/* Hoy */}
      {summary && (
        <div style={{display:'grid', gridTemplateColumns:'repeat(3, 1fr)', gap:12, marginBottom:12}}>
          <Kpi title={`Recibidos ${summary.fecha}`} value={summary.hoy?.recibidos}/>
          <Kpi title={`Entregados ${summary.fecha}`} value={summary.hoy?.entregados}/>
          <Kpi title={`Devoluciones ${summary.fecha}`} value={summary.hoy?.devoluciones}/>
        </div>
      )}

      {/* Por estado */}
      {summary && (
        <div style={{marginBottom:16}}>
          <h4>Paquetes por estado</h4>
          <table border="1" cellPadding="6" width="100%">
            <thead><tr><th>Estado</th><th>Cantidad</th></tr></thead>
            <tbody>
              {summary.byEstado?.map((r,i)=>(
                <tr key={i}><td>{r.estado}</td><td>{r.cantidad}</td></tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Muebles/Ubicaciones con inventario (TODOS, orden descendente) */}
      <div style={{display:'grid', gridTemplateColumns:'1fr 1fr', gap:16}}>
        <div>
          <h4>Muebles con inventario (de mayor a menor)</h4>
          <table border="1" cellPadding="6" width="100%">
            <thead><tr><th>Mueble/Ubicación</th><th>Cantidad</th></tr></thead>
            <tbody>
              {topUbic.map((r,i)=>(
                <tr key={i}>
                  <td>
                    <button
                      onClick={() => openUbicModal(r.ubicacion)}
                      style={{
                        background:'none', border:'none', color:'#0b66c3',
                        textDecoration:'underline', padding:0, cursor:'pointer'
                      }}
                      title="Ver paquetes en este mueble"
                    >
                      {r.ubicacion}
                    </button>
                  </td>
                  <td>{r.cantidad}</td>
                </tr>
              ))}
              {!topUbic.length && (
                <tr><td colSpan={2} style={{textAlign:'center',opacity:.7}}>Sin datos</td></tr>
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
                <th>Ubicación</th>
                <th>Recibido (real)</th>
                <th>Entrada inventario</th>
                <th>Estado</th>
              </tr>
            </thead>
            <tbody>
              {ultimosRec.map((r)=>(
                <tr key={r.id}>
                  <td>{r.tracking_code}</td>
                  <td>{r.marchamo}</td>
                  <td>{r.ubicacion_codigo}</td>
                  <td>{fmtDT(r.received_at)}</td>
                  <td>{fmtDT(r.entrada_inventario_at ?? r.changed_at ?? r.changedAt)}</td>
                  <td>{r.estado}</td>
                </tr>
              ))}
              {!ultimosRec.length && (
                <tr><td colSpan={6} style={{textAlign:'center',opacity:.7}}>Sin datos</td></tr>
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* === SECCIÓN: Ver paquetes por fecha (según recepción REAL) === */}
      <div style={{marginTop:16}}>
        <h4>Ver paquetes por fecha (según recepción REAL)</h4>
        <div style={{display:'flex', gap:8, alignItems:'center', marginBottom:8, flexWrap:'wrap'}}>
          <label>Fecha:
            <input
              type="date"
              value={fechaPF}
              onChange={e => setFechaPF(e.target.value)}
              style={{ marginLeft:4 }}
            />
          </label>
          <button onClick={cargarPorFecha} disabled={pfLoading || !fechaPF}>
            {pfLoading ? 'Cargando…' : 'Ver'}
          </button>
          <div style={{display:'flex', gap:4, flexWrap:'wrap'}}>
            <button
              onClick={()=>setTabPF('RECIBIDOS')}
              style={tabBtnStyle(tabPF === 'RECIBIDOS')}
            >
              Recibidos ({pfData.recibidos.length})
            </button>
            <button
              onClick={()=>setTabPF('ENTREGADOS')}
              style={tabBtnStyle(tabPF === 'ENTREGADOS')}
            >
              Entregados ({pfData.entregados.length})
            </button>
            <button
              onClick={()=>setTabPF('INVENTARIO')}
              style={tabBtnStyle(tabPF === 'INVENTARIO')}
            >
              En inventario ({pfData.inventario.length})
            </button>
          </div>
        </div>

        <div style={{border:'1px solid rgba(22,62,122,.15)', borderRadius:8, padding:8}}>
          <div style={{maxHeight:340, overflowY:'auto'}}>
            <table style={{ width:'100%', borderCollapse:'collapse' }}>
              <thead>
                <tr>
                  <th style={th}>Tracking</th>
                  <th style={th}>Marchamo</th>
                  <th style={th}>Ubicación</th>
                  <th style={th}>Nombre</th>
                  <th style={th}>Descripción</th>
                  <th style={th}>Estado actual</th>
                  <th style={th}>Fecha recepción (real)</th>
                </tr>
              </thead>
              <tbody>
                {currentPFRows.length === 0 ? (
                  <tr>
                    <td colSpan={7} style={{padding:12, textAlign:'center', opacity:.7}}>
                      Sin resultados para la fecha seleccionada
                    </td>
                  </tr>
                ) : currentPFRows.map((r, idx) => (
                  <tr key={r.id ?? r.tracking_code ?? idx} style={{ borderBottom:'1px solid rgba(0,0,0,0.06)' }}>
                    <td style={td}>{r.tracking_code}</td>
                    <td style={td}>{r.marchamo}</td>
                    <td style={td}>{r.ubicacion_codigo}</td>
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

      {/* === NUEVA SECCIÓN: Resumen mensual (matriz tipo hoja) === */}
      <div style={{marginTop:16}}>
        <h4>Resumen mensual (matriz tipo hoja)</h4>
        <div style={{display:'flex', gap:8, alignItems:'center', marginBottom:8, flexWrap:'wrap'}}>
          <label>Mes:
            <input
              type="month"
              value={mesResumen}
              onChange={e => setMesResumen(e.target.value)}
              style={{ marginLeft:4 }}
            />
          </label>
          <button onClick={cargarMatrizMes} disabled={loadingMes || !mesResumen}>
            {loadingMes ? 'Cargando…' : 'Ver mes'}
          </button>
        </div>

        <div style={{border:'1px solid rgba(22,62,122,.15)', borderRadius:8, padding:8}}>
          <div style={{maxHeight:400, overflowY:'auto'}}>
            <table style={{ width:'100%', borderCollapse:'collapse' }}>
              <thead>
                <tr>
                  <th style={th}>Fecha</th>
                  <th style={th}>Inventario</th>
                  <th style={th}>Recibido</th>
                  <th style={th}>Entregado</th>
                  <th style={th}>Enrutes</th>
                  <th style={th}>Otras zonas</th>
                  <th style={th}>Vencidos</th>
                  <th style={th}>No entregar</th>
                  <th style={th}>Transporte</th>
                  <th style={th}>Total</th>
                </tr>
              </thead>
              <tbody>
                {matrizMes.length === 0 ? (
                  <tr>
                    <td colSpan={10} style={{padding:12, textAlign:'center', opacity:.7}}>
                      Sin datos para el mes seleccionado
                    </td>
                  </tr>
                ) : matrizMes.map((r) => (
                  <tr key={r.fecha} style={{ borderBottom:'1px solid rgba(0,0,0,0.06)' }}>
                    <td style={td}>{r.fecha}</td>
                    <td style={td}>{fmtCell(r.inventario)}</td>
                    <td style={td}>{fmtCell(r.recibido)}</td>
                    <td style={td}>{fmtCell(r.entregado)}</td>
                    <td style={td}>{fmtCell(r.enrutes)}</td>
                    <td style={td}>{fmtCell(r.otras_zonas)}</td>
                    <td style={td}>{fmtCell(r.vencidos)}</td>
                    <td style={td}>{fmtCell(r.no_entregar)}</td>
                    <td style={td}>{fmtCell(r.transporte)}</td>
                    <td style={td}>{fmtCell(r.total)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </div>

      {/* Movimientos del día (TODOS) con scroll (máx ~10 filas visibles) */}
      <div style={{marginTop:16}}>
        <h4>Movimientos de estado del {fecha}</h4>
        <div style={{maxHeight:340, overflowY:'auto'}}>
          <table border="1" cellPadding="6" width="100%">
            <thead>
              <tr>
                <th>Tracking</th><th>Marchamo</th><th>Ubicación</th>
                <th>De</th><th>A</th><th>Fecha</th><th>Motivo</th><th>Por</th>
              </tr>
            </thead>
            <tbody>
              {ultimosMov.map((r)=>(
                <tr key={r.hist_id}>
                  <td>{r.tracking_code}</td>
                  <td>{r.marchamo}</td>
                  <td>{r.ubicacion_codigo}</td>
                  <td>{r.estado_from ?? '-'}</td>
                  <td>{r.estado_to}</td>
                  {/* Fecha lógica (real) del movimiento */}
                  <td>{fmtDT(movFechaOficial(r))}</td>
                  <td>{r.motivo ?? '-'}</td>
                  <td>{r.changed_by ?? '-'}</td>
                </tr>
              ))}
              {!ultimosMov.length && (
                <tr><td colSpan={8} style={{textAlign:'center',opacity:.7}}>Sin movimientos para la fecha</td></tr>
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* MODAL: Paquetes por mueble/ubicación (solo INVENTARIO) */}
      {ubicModal.open && (
        <div
          onClick={closeUbicModal}
          style={{
            position:'fixed', inset:0, background:'rgba(0,0,0,0.35)',
            display:'flex', alignItems:'center', justifyContent:'center', zIndex:1000
          }}
        >
          <div
            onClick={e=>e.stopPropagation()}
            style={blueCard}
          >
            <button
              onClick={closeUbicModal}
              aria-label="Cerrar"
              title="Cerrar"
              style={closeBtn}
            >
              ×
            </button>

            <h4 style={{margin:'0 0 10px', color:'#fff'}}>
              Paquetes en: <span style={{fontWeight:800}}>{ubicModal.ubicacion}</span>
              <span style={pill}>INVENTARIO</span>
            </h4>

            {ubicModal.loading && <div style={{padding:8, color:'#e8f0ff'}}>Cargando paquetes…</div>}
            {ubicModal.error && (
              <div style={{padding:8, color:'#ffdde0'}}>
                {ubicModal.error}
              </div>
            )}

            {!ubicModal.loading && !ubicModal.error && (
              <>
                <div style={{marginBottom:8, opacity:.9, color:'#e8f0ff'}}>
                  Total (inventario): {ubicModal.rows.length}
                </div>
                <div style={{overflow:'auto', maxHeight:'65vh', border:'1px solid rgba(255,255,255,.35)', borderRadius:8, background:'#fff'}}>
                  <table style={{ width:'100%', borderCollapse:'collapse' }}>
                    <thead>
                      <tr style={{background:'#f3f7ff'}}>
                        <th style={th}>Tracking</th>
                        <th style={th}>Marchamo</th>
                        <th style={th}>Ubicación</th>
                        <th style={th}>Estado</th>
                        <th style={th}>Recibido</th>
                      </tr>
                    </thead>
                    <tbody>
                      {ubicModal.rows.length ? ubicModal.rows.map((r, idx) => (
                        <tr key={r.id ?? r.tracking_code ?? idx} style={{ borderBottom:'1px solid rgba(0,0,0,0.06)' }}>
                          <td style={td}>{r.tracking_code}</td>
                          <td style={td}>{r.marchamo}</td>
                          <td style={td}>{r.ubicacion_codigo}</td>
                          <td style={td}>{r.estado}</td>
                          <td style={td}>{fmtDT(r.received_at)}</td>
                        </tr>
                      )) : (
                        <tr><td colSpan={5} style={{padding:12, textAlign:'center', opacity:.7}}>Sin paquetes en inventario</td></tr>
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

const th = { textAlign:'left', padding:'8px 10px', borderBottom:'1px solid #dfe7f3', position:'sticky', top:0 }
const td = { padding:'8px 10px', verticalAlign:'top' }

// Tarjeta azul “bonita” al estilo topbar
const blueCard = {
  background:'var(--brand-blue)',
  color:'#fff',
  borderRadius:12,
  padding:16,
  width:'min(900px, 95vw)',
  maxHeight:'80vh',
  boxShadow:'0 10px 30px rgba(0,0,0,0.25)',
  position:'relative',
  border:'2px solid rgba(255,255,255,.2)'
}

const closeBtn = {
  position:'absolute',
  top:8,
  right:10,
  border:'2px solid rgba(255,255,255,.6)',
  background:'transparent',
  color:'#fff',
  fontSize:22,
  lineHeight:1,
  cursor:'pointer',
  borderRadius:8,
  width:36,
  height:36
}

const pill = {
  marginLeft:8,
  background:'var(--brand-green)',
  color:'#fff',
  borderRadius:999,
  padding:'2px 8px',
  fontSize:12,
  fontWeight:700,
  verticalAlign:'middle',
  display:'inline-block'
}

const tabBtnStyle = (active) => ({
  padding:'6px 10px',
  border:'1px solid #28C76F',
  background: active ? '#f6fff9' : '#ffffff',
  color:'#163E7A',
  borderRadius:8,
  fontSize:12,
  fontWeight:600,
  cursor:'pointer'
})

function Kpi({ title, value }){
  return (
    <div style={{background:'#ffffffff', border:'1px solid rgba(22,62,122,.12)', borderRadius:10, padding:12}}>
      <div style={{opacity:.7, fontSize:12}}>{title}</div>
      <div style={{fontSize:28, fontWeight:700}}>{value ?? 0}</div>
    </div>
  )
}

function fmtDT(dt){
  if(!dt) return '-'
  try {
    const d = new Date(dt)
    return d.toLocaleString('es-CR', { timeZone: 'America/Costa_Rica' })
  } catch {
    return String(dt)
  }
}
