import { useEffect, useState } from 'react'
import { api } from '../api'

const CR_TZ = 'America/Costa_Rica'

const toCRISO = (yyyyMmDd, hh = '00', mm = '00', ss = '00') => {
  if (!yyyyMmDd) return null
  return `${yyyyMmDd}T${hh}:${mm}:${ss}-06:00`
}

const ESTADO_LABEL = {
  PRUEBA_DE_ENTREGA: 'Prueba de entrega (POD)',
  ENTREGADO_A_TRANSPORTISTA_LOCAL: 'Entregado a transportista local (Recibido)',
  NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE: 'No entregado - Consignatario no disponible (Inventario)',
  ENTREGADO_A_TRANSPORTISTA_LOCAL_2DO_INTENTO: 'Entregado a transportista local - 2do intento (Inventario)',
  NO_ENTREGABLE: 'No entregable - Retornado a oficina local (Devolución)',
  NO_ENTREGABLE__FUERA_DE_RUTA: '↳ Devolución: Fuera de ruta',
  NO_ENTREGABLE__VENCIDOS: '↳ Devolución: Vencidos',
  NO_ENTREGABLE__DOS_INTENTOS: '↳ Devolución: Dos intentos',
}

const labelEstado = (code) => {
  const k = String(code ?? '').toUpperCase()
  return ESTADO_LABEL[k] || (code ?? '-')
}

const ESTADOS_INVENTARIO = new Set([
  'ENTREGADO_A_TRANSPORTISTA_LOCAL',
  'NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE',
  'ENTREGADO_A_TRANSPORTISTA_LOCAL_2DO_INTENTO',
])

function normalizeByEstado(arr) {
  const a = Array.isArray(arr) ? arr : []
  return a.map(r => ({
    estado: String(r?.estado ?? '').toUpperCase(),
    cantidad: Number(r?.cantidad ?? r?.total ?? r?.count ?? 0) || 0,
  }))
}

function calcInventarioDesdeByEstado(byEstado) {
  const rows = normalizeByEstado(byEstado)
  return rows.reduce((acc, r) => acc + (ESTADOS_INVENTARIO.has(r.estado) ? r.cantidad : 0), 0)
}

export default function Dashboard() {
  const [fecha, setFecha] = useState(() =>
    new Intl.DateTimeFormat('en-CA', { timeZone: CR_TZ }).format(new Date())
  )

  const [mesResumen, setMesResumen] = useState(() => {
    const hoy = new Intl.DateTimeFormat('en-CA', { timeZone: CR_TZ }).format(new Date())
    return hoy.slice(0, 7)
  })
  const [matrizMes, setMatrizMes] = useState([])
  const [loadingMes, setLoadingMes] = useState(false)

  const [summary, setSummary] = useState(null)
  const [topDistritos, setTopDistritos] = useState([])
  const [topTransportistas, setTopTransportistas] = useState([])
  const [ultimosMov, setUltimosMov] = useState([])
  const [loading, setLoading] = useState(false)

  const [distModal, setDistModal] = useState({
    open: false,
    distrito: '',
    rows: [],
    loading: false,
    error: null,
  })

  const [transModal, setTransModal] = useState({
    open: false,
    mensajeroId: null,
    transportista: '',
    rows: [],
    loading: false,
    error: null,
  })

  const [fechaPF, setFechaPF] = useState(() =>
    new Intl.DateTimeFormat('en-CA', { timeZone: CR_TZ }).format(new Date())
  )
  const [tabPF, setTabPF] = useState('RECIBIDOS')
  const [pfData, setPfData] = useState({
    recibidos: [],
    noEntregados: [],
    entregados: [],
    noEntregables: [],
  })
  const [pfLoading, setPfLoading] = useState(false)

  const cargarTopDistritos = async () => {
    try {
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
      const [s, t, m] = await Promise.all([
        api.get('/dashboard/summary', { params: { fecha } }),
        api.get('/dashboard/top-transportistas', { params: { limit: 100000 } }),
        api.get('/dashboard/ultimos-movimientos', { params: { fecha, limit: 100000 } }),
      ])

      setSummary(s.data)

      const tArr = Array.isArray(t.data) ? t.data : []
      const tNorm = tArr.map(x => ({
        mensajero_id: Number(x?.mensajero_id ?? x?.mensajeroId ?? 0) || 0,
        transportista: x?.transportista ?? x?.full_name ?? x?.nombre ?? '',
        cantidad: Number(x?.cantidad ?? x?.total ?? x?.count ?? 0) || 0,
      })).filter(x => x.transportista && x.mensajero_id)

      tNorm.sort((a, b) => (b.cantidad ?? 0) - (a.cantidad ?? 0) || String(a.transportista).localeCompare(String(b.transportista)))
      setTopTransportistas(tNorm)

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
      const [s, u, t, m] = await Promise.all([
        api.get('/dashboard/summary', { params: { fecha } }),
        api.get('/dashboard/top-distritos', { params: { limit: 100000 } }),
        api.get('/dashboard/top-transportistas', { params: { limit: 100000 } }),
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

      const tArr = Array.isArray(t.data) ? t.data : []
      const tNorm = tArr.map(x => ({
        mensajero_id: Number(x?.mensajero_id ?? x?.mensajeroId ?? 0) || 0,
        transportista: x?.transportista ?? x?.full_name ?? x?.nombre ?? '',
        cantidad: Number(x?.cantidad ?? x?.total ?? x?.count ?? 0) || 0,
      })).filter(x => x.transportista && x.mensajero_id)

      tNorm.sort((a, b) => (b.cantidad ?? 0) - (a.cantidad ?? 0) || String(a.transportista).localeCompare(String(b.transportista)))
      setTopTransportistas(tNorm)

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

  const cargarMatrizMes = async () => {
    if (!mesResumen) return
    setLoadingMes(true)
    try {
      const [yStr, mStr] = mesResumen.split('-')
      const year = parseInt(yStr, 10)
      const month = parseInt(mStr, 10)

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
            if (Array.isArray(value.result)) return value.result[0] ?? null
            if (Array.isArray(value.rows)) return value.rows[0] ?? null
          }
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

        const fueraDeRuta = getField(
          'fuera_de_ruta', 'FUERA_DE_RUTA', 'fuera_ruta', 'FUERA_RUTA',
          'dev_fuera_de_ruta', 'DEV_FUERA_DE_RUTA',
          'enrutes', 'ENRUTES', 'dev_enrute', 'DEV_ENRUTE', 'devoluciones_enrute'
        )

        const vencidos = getField(
          'vencidos', 'VENCIDOS', 'dev_vencidos', 'DEV_VENCIDOS'
        )

        const dosIntentos = getField(
          'dos_intentos', 'DOS_INTENTOS', 'dev_dos_intentos', 'DEV_DOS_INTENTOS',
          'segundo_intento', 'SEGUNDO_INTENTO', '2_intentos', 'DOS_INTENTOS',
          'otras_zonas', 'OTRAS_ZONAS', 'dev_otras_zonas', 'DEV_OTRAS_ZONAS'
        )

        const totalDevDirecto = getField(
          'no_entregar', 'NO_ENTREGAR',
          'no_entregable', 'NO_ENTREGABLE',
          'dev_no_entregable', 'DEV_NO_ENTREGABLE',
          'devolucion', 'DEVOLUCION',
          'devoluciones', 'DEVOLUCIONES'
        )

        const anySubtype = (fueraDeRuta != null) || (vencidos != null) || (dosIntentos != null)
        const totalDevFallback = anySubtype
          ? (Number(fueraDeRuta ?? 0) + Number(vencidos ?? 0) + Number(dosIntentos ?? 0))
          : null

        return {
          inventario: getField('inventario', 'INVENTARIO', 'inv_inicial', 'inventario_inicial'),
          recibido: getField('recibido', 'RECIBIDO', 'recibidos', 'RECIBIDOS'),
          entregado: getField('entregado', 'ENTREGADO', 'pod', 'POD', 'prueba_de_entrega', 'PRUEBA_DE_ENTREGA'),
          fuera_de_ruta: fueraDeRuta,
          vencidos,
          dos_intentos: dosIntentos,
          no_entregar: totalDevDirecto != null ? totalDevDirecto : totalDevFallback,
          tr_a_ca: getField('tr_a_ca', 'TR_A_CA', 'trACa', 'TRACA'),
          total: getField('total', 'TOTAL', 'inv_final', 'inventario_final')
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

  useEffect(() => {
    cargarMatrizMes()
  }, [mesResumen])

  useEffect(() => { cargarTopDistritos() }, [])
  useEffect(() => { cargarFecha() }, [fecha])

  const openDistritoModal = async (distrito) => {
    setDistModal({ open: true, distrito, rows: [], loading: true, error: null })
    try {
      const { data } = await api.get(`/busqueda/distrito/${encodeURIComponent(distrito)}`, {
        params: {
          estado: 'ENTREGADO_A_TRANSPORTISTA_LOCAL,NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE,ENTREGADO_A_TRANSPORTISTA_LOCAL_2DO_INTENTO'
        }
      })
      const arr = Array.isArray(data) ? data : []
      const rows = arr
        .filter(r => ESTADOS_INVENTARIO.has(String(r?.estado ?? '').toUpperCase()))
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

  const openTransportistaModal = async (mensajeroId, transportista) => {
    setTransModal({ open: true, mensajeroId, transportista, rows: [], loading: true, error: null })
    try {
      const { data } = await api.get('/dashboard/pods-transportista', {
        params: { mensajeroId, limit: 100000 }
      })
      const arr = Array.isArray(data) ? data : []
      const rows = arr.sort((a, b) => new Date(b?.delivered_at ?? 0).getTime() - new Date(a?.delivered_at ?? 0).getTime())
      setTransModal(prev => ({ ...prev, rows, loading: false }))
    } catch (e) {
      setTransModal(prev => ({
        ...prev,
        loading: false,
        error: e?.response?.data?.message || e?.message || 'Error cargando paquetes'
      }))
    }
  }

  const closeTransportistaModal = () => setTransModal(prev => ({ ...prev, open: false }))

  useEffect(() => {
    const onKey = (e) => {
      if (e.key === 'Escape') {
        if (distModal.open) closeDistritoModal()
        if (transModal.open) closeTransportistaModal()
      }
    }
    if (distModal.open || transModal.open) window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [distModal.open, transModal.open])

  function movFechaOficial(r) {
    if (!r) return null
    const to = String(r.estado_to ?? r.estadoTo ?? '').toUpperCase()
    if (to === 'NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE' && r.received_at) return r.received_at
    if (to === 'NO_ENTREGABLE' && r.returned_at) return r.returned_at
    if (to === 'PRUEBA_DE_ENTREGA' && (r.delivered_at || r.changed_at || r.changedAt)) return (r.delivered_at ?? r.changed_at ?? r.changedAt)
    return r.changed_at ?? r.changedAt ?? null
  }

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

      const entregados = recibidos.filter(r =>
        String(r?.estado ?? '').toUpperCase() === 'PRUEBA_DE_ENTREGA'
      )

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
  const inventarioRealHoy = summary ? calcInventarioDesdeByEstado(summary.byEstado) : 0
  const fmtCell = (v) => (v === null || v === undefined || v === '' ? '-' : v)

  return (
    <div>
      <h3>Dashboard</h3>

      <div style={{ display: 'flex', gap: 12, alignItems: 'center', marginBottom: 12, flexWrap: 'wrap' }}>
        <label>Fecha:
          <input type="date" value={fecha} onChange={e => setFecha(e.target.value)} />
        </label>

        <button onClick={cargarTodo} disabled={loading}>
          {loading ? 'Actualizando…' : 'Actualizar'}
        </button>
      </div>

      {summary && (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 12, marginBottom: 12 }}>
          <Kpi title="Paquetes totales" value={summary.totales?.paquetes ?? summary.totalPaquetes ?? 0} />
          <Kpi title="En inventario" value={inventarioRealHoy ?? 0} />
        </div>
      )}

      {summary && (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 12, marginBottom: 12 }}>
          <Kpi title={`Recibidos ${summary.fecha ?? fecha}`} value={summary.hoy?.recibidos ?? 0} />
          <Kpi title={`Entregados (POD) ${summary.fecha ?? fecha}`} value={summary.hoy?.entregados ?? 0} />
          <Kpi
            title={`No entregables (Devolución) ${summary.fecha ?? fecha}`}
            value={summary.hoy?.noEntregable ?? summary.hoy?.no_entregable ?? summary.hoy?.devoluciones ?? 0}
          />
        </div>
      )}

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
                      title="Ver paquetes en este distrito (en inventario)"
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
          <h4>Paquetes por mensajero</h4>

          <table border="1" cellPadding="6" width="100%">
            <thead><tr><th>Transportista</th><th>Cantidad</th></tr></thead>
            <tbody>
              {topTransportistas.map((r, i) => (
                <tr key={r.mensajero_id ?? i}>
                  <td>
                    <button
                      onClick={() => openTransportistaModal(r.mensajero_id, r.transportista)}
                      style={{
                        background: 'none', border: 'none', color: '#0b66c3',
                        textDecoration: 'underline', padding: 0, cursor: 'pointer'
                      }}
                      title="Ver paquetes POD de este transportista"
                    >
                      {r.transportista}
                    </button>
                  </td>
                  <td>{r.cantidad}</td>
                </tr>
              ))}
              {!topTransportistas.length && (
                <tr><td colSpan={2} style={{ textAlign: 'center', opacity: .7 }}>Sin datos</td></tr>
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
            <button onClick={() => setTabPF('NO_ENTREGADOS')} style={tabBtnStyle(tabPF === 'NO_ENTREGADOS')}>
              No entregados ({pfData.noEntregados.length})
            </button>
            <button onClick={() => setTabPF('ENTREGADOS')} style={tabBtnStyle(tabPF === 'ENTREGADOS')}>
              Entregados (POD) ({pfData.entregados.length})
            </button>
            <button onClick={() => setTabPF('NO_ENTREGABLES')} style={tabBtnStyle(tabPF === 'NO_ENTREGABLES')}>
              No entregables (Devolución) ({pfData.noEntregables.length})
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

      <div style={{ marginTop: 16 }}>
        <h4>Resumen mensual (matriz tipo hoja)</h4>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 8, flexWrap: 'wrap' }}>
          <label>Mes:
            <input
              type="month"
              value={mesResumen}
              onChange={e => setMesResumen(e.target.value)}
              style={{ marginLeft: 4 }}
            />
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
                  <th style={th}>Entregado (POD)</th>
                  <th style={th}>Fuera de ruta</th>
                  <th style={th}>Vencidos</th>
                  <th style={th}>2 intentos</th>
                  <th style={th}>No entregable (Devolución)</th>
                  <th style={th}>TR a CA</th>
                  <th style={th}>Total</th>
                </tr>
              </thead>
              <tbody>
                {matrizMes.length === 0 ? (
                  <tr>
                    <td colSpan={10} style={{ padding: 12, textAlign: 'center', opacity: .7 }}>
                      Sin datos para el mes seleccionado
                    </td>
                  </tr>
                ) : matrizMes.map((r) => (
                  <tr key={r.fecha} style={{ borderBottom: '1px solid rgba(0,0,0,0.06)' }}>
                    <td style={td}>{r.fecha}</td>
                    <td style={td}>{fmtCell(r.inventario)}</td>
                    <td style={td}>{fmtCell(r.recibido)}</td>
                    <td style={td}>{fmtCell(r.entregado)}</td>
                    <td style={td}>{fmtCell(r.fuera_de_ruta)}</td>
                    <td style={td}>{fmtCell(r.vencidos)}</td>
                    <td style={td}>{fmtCell(r.dos_intentos)}</td>
                    <td style={td}>{fmtCell(r.no_entregar)}</td>
                    <td style={td}>{fmtCell(r.tr_a_ca)}</td>
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
              <span style={pill}>EN INVENTARIO</span>
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

      {transModal.open && (
        <div
          onClick={closeTransportistaModal}
          style={{
            position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.35)',
            display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1000
          }}
        >
          <div onClick={e => e.stopPropagation()} style={blueCard}>
            <button onClick={closeTransportistaModal} aria-label="Cerrar" title="Cerrar" style={closeBtn}>×</button>

            <h4 style={{ margin: '0 0 10px', color: '#fff' }}>
              POD por: <span style={{ fontWeight: 800 }}>{transModal.transportista}</span>
              <span style={pill}>PRUEBA DE ENTREGA</span>
            </h4>

            {transModal.loading && <div style={{ padding: 8, color: '#e8f0ff' }}>Cargando paquetes…</div>}
            {transModal.error && <div style={{ padding: 8, color: '#ffdde0' }}>{transModal.error}</div>}

            {!transModal.loading && !transModal.error && (
              <>
                <div style={{ marginBottom: 8, opacity: .9, color: '#e8f0ff' }}>
                  Total: {transModal.rows.length}
                </div>

                <div style={{ overflow: 'auto', maxHeight: '65vh', border: '1px solid rgba(255,255,255,.35)', borderRadius: 8, background: '#fff' }}>
                  <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                    <thead>
                      <tr style={{ background: '#f3f7ff' }}>
                        <th style={th}>Tracking</th>
                        <th style={th}>Marchamo</th>
                        <th style={th}>Distrito</th>
                        <th style={th}>Estado</th>
                        <th style={th}>Entregado</th>
                      </tr>
                    </thead>
                    <tbody>
                      {transModal.rows.length ? transModal.rows.map((r, idx) => (
                        <tr key={r.id ?? r.tracking_code ?? idx} style={{ borderBottom: '1px solid rgba(0,0,0,0.06)' }}>
                          <td style={td}>{r.tracking_code}</td>
                          <td style={td}>{r.marchamo}</td>
                          <td style={td}>{r.distrito_nombre ?? '-'}</td>
                          <td style={td}>{labelEstado(r.estado)}</td>
                          <td style={td}>{fmtDT(r.delivered_at)}</td>
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