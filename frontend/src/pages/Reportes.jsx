import { useEffect, useState } from 'react'
import { api } from '../api'
import * as XLSX from 'xlsx'

const CR_TZ = 'America/Costa_Rica'

// YYYY-MM-DD -> ISO con offset CR fijo (-06:00)
const toCRISO = (yyyyMmDd, hh = '00', mm = '00', ss = '00') => {
  if (!yyyyMmDd) return null
  return `${yyyyMmDd}T${hh}:${mm}:${ss}-06:00`
}

// En inventario = unión de los 3 primeros
const ESTADOS_INVENTARIO = new Set([
  'ENTREGADO_A_TRANSPORTISTA_LOCAL',
  'NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE',
  'ENTREGADO_A_TRANSPORTISTA_LOCAL_2DO_INTENTO',
])

const SHEET_TITLES = {
  ENTREGADO_A_TRANSPORTISTA_LOCAL: 'Entregado TL',
  NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE: 'No entregado',
  ENTREGADO_A_TRANSPORTISTA_LOCAL_2DO_INTENTO: 'Entregado TL 2do',
  NO_ENTREGABLE: 'No entregable',
  EN_INVENTARIO: 'En inventario',
}

// Columnas fijas en tablas/export
const FIXED_HEADERS = {
  marchamo: 'MARCHAMO',
  mueble: 'MUEBLE',
  tracking: 'TRACKING',
  tracking_intranet: 'TRACKING INTRANET',
  nombre: 'NOMBRE',
  descripcion: 'DESCRIPCION',
  estado: 'ESTADO',
  devolucion_subtipo: 'SUBTIPO DEVOLUCION',
  telefono: 'TELEFONO',
  direccion: 'DIRECCION',
  fecha: 'FECHA',
}

const FIXED_KEYS = [
  'marchamo',
  'mueble',
  'tracking',
  'nombre',
  'descripcion',
  'estado',
  'devolucion_subtipo',
  'telefono',
  'direccion',
  'fecha',
]

const FIXED_KEYS_XLSX = [
  'marchamo',
  'mueble',
  'tracking',
  'tracking_intranet',
  'nombre',
  'descripcion',
  'estado',
  'devolucion_subtipo',
  'telefono',
  'direccion',
  'fecha',
]

const esCR = { timeZone: CR_TZ }

function labelEstado(e) {
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

function fmtDMY(dt) {
  if (!dt) return '-'
  try {
    const d = new Date(dt)
    return d.toLocaleDateString('es-CR', { ...esCR, year: 'numeric', month: '2-digit', day: '2-digit' })
  } catch {
    return String(dt)
  }
}

function uniqByTracking(rows) {
  const seen = new Set()
  const out = []
  for (const r of rows || []) {
    const t = String(r?.tracking_code || '')
    if (!t) continue
    if (seen.has(t)) continue
    seen.add(t)
    out.push(r)
  }
  return out
}

function safeSheetName(name) {
  const s = String(name || '').trim() || 'Sheet'
  return s.slice(0, 31).replace(/[\[\]\*\/\\\?\:]/g, '_')
}

function autoWidth(ws, rows, headers) {
  const colWidths = headers.map(h => ({ wch: Math.max(10, String(h).length + 2) }))
  for (const r of rows) {
    headers.forEach((h, i) => {
      const v = r?.[h]
      const len = v == null ? 0 : String(v).length
      colWidths[i].wch = Math.max(colWidths[i].wch, Math.min(60, len + 2))
    })
  }
  ws['!cols'] = colWidths
}

function downloadBlob(blob, filename) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}

export default function Reportes() {
  const hoy = new Intl.DateTimeFormat('en-CA', esCR).format(new Date())

  const [mode, setMode] = useState('dia')
  const [fecha, setFecha] = useState(hoy)
  const [desde, setDesde] = useState(hoy)
  const [hasta, setHasta] = useState(hoy)

  // Si está apagado, el reporte es un "snapshot" del estado actual (sin rango de fechas)
  const [filtrarFechas, setFiltrarFechas] = useState(false)

  const [loading, setLoading] = useState(false)

  // 4 estados
  const [stEntregadoTL, setStEntregadoTL] = useState([])
  const [stNoEntregado, setStNoEntregado] = useState([])
  const [stEntregado2do, setStEntregado2do] = useState([])
  const [stNoEntregable, setStNoEntregable] = useState([])

  // implícito: unión de 3 estados que SI están en inventario
  const [stInventario, setStInventario] = useState([])

  const [exportFormat, setExportFormat] = useState('xlsx')

  useEffect(() => { consultar() }, [mode, fecha, desde, hasta, filtrarFechas])

  const consultar = async () => {
    setLoading(true)
    try {
      // Snapshot actual (sin filtro) por defecto.
      // Si el usuario activa "Filtrar por fecha", usamos last_state_change_at (ELSE del SP).
      let paramsBase = {}
      if (filtrarFechas) {
        let iniISO = null, finISO = null
        if (mode === 'dia') {
          iniISO = toCRISO(fecha, '00', '00', '00')
          finISO = toCRISO(fecha, '23', '59', '59')
        } else {
          if (!desde && !hasta) { setLoading(false); return }
          iniISO = desde ? toCRISO(desde, '00', '00', '00') : null
          finISO = hasta ? toCRISO(hasta, '23', '59', '59') : null
        }

        paramsBase = {
          // CAMBIO no existe literal en el SP, pero cae en el ELSE => last_state_change_at
          tipoFecha: 'CAMBIO',
          ...(iniISO && { desde: iniISO }),
          ...(finISO && { hasta: finISO }),
        }
      }

      const [rETL, rNE, rE2, rNEN] = await Promise.all([
        api.get('/busqueda/estado', { params: { ...paramsBase, estado: 'ENTREGADO_A_TRANSPORTISTA_LOCAL' } }),
        api.get('/busqueda/estado', { params: { ...paramsBase, estado: 'NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE' } }),
        api.get('/busqueda/estado', { params: { ...paramsBase, estado: 'ENTREGADO_A_TRANSPORTISTA_LOCAL_2DO_INTENTO' } }),
        api.get('/busqueda/estado', { params: { ...paramsBase, estado: 'NO_ENTREGABLE' } }),
      ])

      const aETL = Array.isArray(rETL.data) ? rETL.data : []
      const aNE  = Array.isArray(rNE.data) ? rNE.data : []
      const aE2  = Array.isArray(rE2.data) ? rE2.data : []
      const aNEN = Array.isArray(rNEN.data) ? rNEN.data : []

      setStEntregadoTL(aETL)
      setStNoEntregado(aNE)
      setStEntregado2do(aE2)
      setStNoEntregable(aNEN)

      // “En inventario” = unión deduplicada por tracking
      setStInventario(uniqByTracking([...aETL, ...aNE, ...aE2]))
    } catch (e) {
      alert(e?.response?.data?.message || e?.message || 'Error')
    } finally {
      setLoading(false)
    }
  }

  const resetear = () => {
    setMode('dia'); setFecha(hoy); setDesde(hoy); setHasta(hoy)
    setFiltrarFechas(false)
    setStEntregadoTL([]); setStNoEntregado([]); setStEntregado2do([]); setStNoEntregable([]); setStInventario([])
  }

  // Proyección fija para tablas/export
  const projectRows = (rows, dateKey) => {
    return (rows || []).map(r => ({
      marchamo: r?.marchamo ?? '-',
      mueble: r?.distrito_nombre ?? '-',
      tracking: r?.tracking_code ?? '-',
      nombre: r?.recipient_name ?? '-',
      descripcion: r?.content_description ?? '-',
      estado: labelEstado(r?.estado ?? '-'),
      devolucion_subtipo: (String(r?.estado ?? '').toUpperCase() === 'NO_ENTREGABLE')
        ? (r?.devolucion_subtipo ?? '-')
        : '-',
      telefono: r?.recipient_phone ?? '-',
      direccion: r?.recipient_address ?? '-',
      fecha: fmtDMY(r?.[dateKey]),
    }))
  }

  const generarReporte = () => {
    const addSheetFixed = (wb, nombre, filas, dateKey) => {
      const base = projectRows(filas, dateKey)
      const withIntranet = base.map(d => ({
        ...d,
        tracking_intranet: (d.tracking && d.tracking !== '-') ? `${d.tracking},` : '-',
      }))
      const headers = FIXED_KEYS_XLSX.map(k => FIXED_HEADERS[k])
      const rows = withIntranet.map(d =>
        Object.fromEntries(FIXED_KEYS_XLSX.map(k => [FIXED_HEADERS[k], d[k]]))
      )
      const ws = XLSX.utils.json_to_sheet(rows, { header: headers })
      autoWidth(ws, rows, headers)
      XLSX.utils.book_append_sheet(wb, ws, safeSheetName(nombre))
    }

    const stamp = !filtrarFechas
      ? `actual_${hoy}`
      : (mode === 'dia' ? fecha : `${desde}_${hasta}`)

    const dateKey = 'last_state_change_at'

    if (exportFormat === 'xlsx') {
      const wb = XLSX.utils.book_new()
      addSheetFixed(wb, SHEET_TITLES.ENTREGADO_A_TRANSPORTISTA_LOCAL, stEntregadoTL, dateKey)
      addSheetFixed(wb, SHEET_TITLES.NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE, stNoEntregado, dateKey)
      addSheetFixed(wb, SHEET_TITLES.ENTREGADO_A_TRANSPORTISTA_LOCAL_2DO_INTENTO, stEntregado2do, dateKey)
      addSheetFixed(wb, SHEET_TITLES.NO_ENTREGABLE, stNoEntregable, dateKey)
      addSheetFixed(wb, SHEET_TITLES.EN_INVENTARIO, stInventario, dateKey)
      XLSX.writeFile(wb, `reporte_estados_${stamp}.xlsx`, { compression: true })
      return
    }

    // CSV (una sola hoja: todo junto)
    const all = [
      ...projectRows(stEntregadoTL, dateKey),
      ...projectRows(stNoEntregado, dateKey),
      ...projectRows(stEntregado2do, dateKey),
      ...projectRows(stNoEntregable, dateKey),
      ...projectRows(stInventario, dateKey),
    ]

    const allWithIntranet = all.map(d => ({
      ...d,
      tracking_intranet: (d.tracking && d.tracking !== '-') ? `${d.tracking},` : '-',
    }))

    const headers = FIXED_KEYS_XLSX.map(k => FIXED_HEADERS[k])
    const rows = allWithIntranet.map(d => Object.fromEntries(FIXED_KEYS_XLSX.map(k => [FIXED_HEADERS[k], d[k]])))
    const ws = XLSX.utils.json_to_sheet(rows, { header: headers })
    const csv = XLSX.utils.sheet_to_csv(ws)

    const blob = new Blob([new Uint8Array([0xEF, 0xBB, 0xBF]), csv], { type: 'text/csv;charset=utf-8;' })
    downloadBlob(blob, `reporte_estados_${stamp}.csv`)
  }

  const kpi = [
    { title: 'Entregado TL', value: stEntregadoTL.length },
    { title: 'No entregado', value: stNoEntregado.length },
    { title: 'Entregado TL 2do', value: stEntregado2do.length },
    { title: 'No entregable', value: stNoEntregable.length },
    { title: 'En inventario', value: stInventario.length },
  ]

  return (
    <div>
      <h3>Reportes</h3>

      <div style={{ display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap', marginBottom: 12 }}>
        <label style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <input
            type="checkbox"
            checked={filtrarFechas}
            onChange={(e) => setFiltrarFechas(e.target.checked)}
          />
          Filtrar por fecha (último cambio)
        </label>

        <button onClick={generarReporte} disabled={loading || totalFilas() === 0}>
          Generar reporte
        </button>

        <select value={exportFormat} onChange={(e) => setExportFormat(e.target.value)}>
          <option value="xlsx">Excel (.xlsx)</option>
          <option value="csv">CSV (.csv)</option>
        </select>

        <button onClick={resetear} disabled={loading}>Reiniciar</button>
      </div>

      {filtrarFechas && (
        <div style={{ display: 'flex', gap: 8, marginBottom: 12 }}>
          <button onClick={() => setMode('dia')} style={btnModeStyle(mode === 'dia')}>Por día</button>
          <button onClick={() => setMode('rango')} style={btnModeStyle(mode === 'rango')}>Rango de fechas</button>
        </div>
      )}

      {filtrarFechas && (
        mode === 'dia' ? (
          <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 12, flexWrap: 'wrap' }}>
            <label>Fecha:
              <input type="date" value={fecha} onChange={e => setFecha(e.target.value)} />
            </label>
          </div>
        ) : (
          <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 12, flexWrap: 'wrap' }}>
            <label>Desde:
              <input type="date" value={desde} onChange={e => setDesde(e.target.value)} />
            </label>
            <label>Hasta:
              <input type="date" value={hasta} onChange={e => setHasta(e.target.value)} />
            </label>
          </div>
        )
      )}

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(5, 1fr)', gap: 12, margin: '12px 0' }}>
        {kpi.map((k, i) => <Kpi key={i} title={k.title} value={k.value} />)}
      </div>

      <section style={{ marginTop: 8 }}>
        <h4>Entregado a transportista local {filtrarFechas ? (mode === 'dia' ? `(${fecha})` : rangoLabel(desde, hasta)) : '(Actual)'}</h4>
        <DataTable rows={stEntregadoTL} dateKey="last_state_change_at" />
      </section>

      <section style={{ marginTop: 16 }}>
        <h4>No entregado - Consignatario no disponible {filtrarFechas ? (mode === 'dia' ? `(${fecha})` : rangoLabel(desde, hasta)) : '(Actual)'}</h4>
        <DataTable rows={stNoEntregado} dateKey="last_state_change_at" />
      </section>

      <section style={{ marginTop: 16 }}>
        <h4>Entregado a transportista local - 2do intento {filtrarFechas ? (mode === 'dia' ? `(${fecha})` : rangoLabel(desde, hasta)) : '(Actual)'}</h4>
        <DataTable rows={stEntregado2do} dateKey="last_state_change_at" />
      </section>

      <section style={{ marginTop: 16 }}>
        <h4>No entregable - Retornado a oficina local {filtrarFechas ? (mode === 'dia' ? `(${fecha})` : rangoLabel(desde, hasta)) : '(Actual)'}</h4>
        <DataTable rows={stNoEntregable} dateKey="last_state_change_at" />
      </section>

      <section style={{ marginTop: 16 }}>
        <h4>En inventario {filtrarFechas ? (mode === 'dia' ? `(${fecha})` : rangoLabel(desde, hasta)) : '(Actual)'}</h4>
        <DataTable rows={stInventario} dateKey="last_state_change_at" />
      </section>
    </div>
  )

  function totalFilas() {
    return stEntregadoTL.length + stNoEntregado.length + stEntregado2do.length + stNoEntregable.length + stInventario.length
  }
}

const btnModeStyle = (active) => ({
  padding: '8px 14px',
  border: '2px solid #28C76F',
  background: active ? '#f6fff9' : '#ffffff',
  color: '#163E7A',
  borderRadius: 10,
  fontWeight: 600
})

function Kpi({ title, value }) {
  return (
    <div style={{ background: '#ffffff', border: '1px solid rgba(22,62,122,.12)', borderRadius: 12, padding: 12 }}>
      <div style={{ opacity: .85, fontSize: 12, color: '#163E7A' }}>{title}</div>
      <div style={{ fontSize: 28, fontWeight: 700, color: '#163E7A' }}>{value ?? 0}</div>
    </div>
  )
}

function rangoLabel(desde, hasta) {
  const mk = (ymd) => ymd ? new Date(`${ymd}T00:00:00-06:00`) : null
  const a = mk(desde), b = mk(hasta)
  const fmt = (d) => d ? d.toLocaleDateString('es-CR', { timeZone: CR_TZ }) : '—'
  if (a && b) return `(${fmt(a)} → ${fmt(b)})`
  if (a) return `(desde ${fmt(a)})`
  if (b) return `(hasta ${fmt(b)})`
  return ''
}

function DataTable({ rows, dateKey }) {
  const data = (rows || []).map(r => ({
    marchamo: r?.marchamo ?? '-',
    mueble: r?.distrito_nombre ?? '-',
    tracking: r?.tracking_code ?? '-',
    nombre: r?.recipient_name ?? '-',
    descripcion: r?.content_description ?? '-',
    estado: labelEstado(r?.estado ?? '-'),
    devolucion_subtipo: (String(r?.estado ?? '').toUpperCase() === 'NO_ENTREGABLE')
      ? (r?.devolucion_subtipo ?? '-')
      : '-',
    telefono: r?.recipient_phone ?? '-',
    direccion: r?.recipient_address ?? '-',
    fecha: fmtDMY(r?.[dateKey]),
  }))

  return (
    <div style={{ maxHeight: '70vh', overflowY: 'auto' }}>
      <table style={{ width: '100%', borderCollapse: 'collapse', tableLayout: 'auto' }}>
        <thead>
          <tr>
            {FIXED_KEYS.map(k => (
              <th key={k} style={{ whiteSpace: 'nowrap', padding: '8px 10px', textAlign: 'left' }}>
                {FIXED_HEADERS[k]}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {data.length === 0 ? (
            <tr><td colSpan={FIXED_KEYS.length} style={{ textAlign: 'center', opacity: .7, padding: 12 }}>Sin resultados</td></tr>
          ) : data.map((r, i) => (
            <tr key={`${r.tracking}-${i}`} style={{ borderBottom: '1px solid rgba(0,0,0,.06)' }}>
              {FIXED_KEYS.map(col => (
                <td key={col} style={{ padding: '8px 10px', whiteSpace: 'nowrap' }}>
                  {r[col]}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
