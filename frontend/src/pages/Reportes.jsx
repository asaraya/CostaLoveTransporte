import { useEffect, useState } from 'react'
import { api } from '../api'
import * as XLSX from 'xlsx'

/* columnas a ocultar (no afecta las proyecciones fijas de la tabla/export) */
const EXCLUDED_COLUMNS = new Set([
  'status_externo', 'status_externo_at',
  'caja','saco_id','ubicacion_id','ubicacion_tipo','estanteria',
  // OJO: NO excluir 'mueble' para que esté disponible
])

const HEADERS_ES = {
  tracking_code: 'Numero de envio',
  tracking_intranet: 'Tracking Intranet',
  marchamo: 'Marchamo',
  ubicacion_codigo: 'Ubicacion',
  estado: 'Estado',
  received_at: 'Recepcion',
  delivered_at: 'Entrega',
  returned_at: 'Devolucion',
  last_state_change_at: 'Ultimo cambio',
  recipient_name: 'Destinatario',
  recipient_address: 'Direccion',
  recipient_phone: 'Telefono',
  content_description: 'Descripcion',
  // devolucion_subtipo: 'Subcategoría devolución',
}

/* ====== Definición de salida fija (UI) ====== */
const FIXED_KEYS = [
  'marchamo', 'mueble', 'tracking', 'nombre', 'descripcion',
  'estado', 'devolucion_subtipo',
  'telefono', 'direccion', 'fecha'
]

/* ====== Definición de salida fija (Excel) → agrega Tracking Intranet ====== */
const FIXED_KEYS_XLSX = [
  'marchamo', 'mueble', 'tracking', 'tracking_intranet',
  'nombre', 'descripcion',
  'estado', 'devolucion_subtipo',
  'telefono', 'direccion', 'fecha'
]

const FIXED_HEADERS = {
  marchamo: 'Marchamo',
  mueble: 'Mueble',
  tracking: 'Tracking',
  tracking_intranet: 'Tracking Intranet', // ← SOLO Excel
  nombre: 'Nombre',
  descripcion: 'Descripción',
  estado: 'Estado',
  devolucion_subtipo: 'Subcategoría devolución',
  telefono: 'Teléfono',
  direccion: 'Dirección',
  fecha: 'Fecha',
}

/* Nombres de las hojas del Excel */
const SHEET_TITLES = {
  recibidos: 'Listo para retiro en tienda Aeropost',
  entregados: 'Prueba de Entrega',
  devoluciones: 'En tránsito a bodegas Aeropost',
  push: 'Push',
  almacenaje: 'Almacenaje',
  inventario: 'Inventario (EN_INVENTARIO)',
}

/* Excel no permite : \ / ? * [ ] y máximo 31 caracteres */
const safeSheetName = (name) => {
  const cleaned = name.replace(/[:\\\/\?\*\[\]]/g, '')
  return cleaned.length <= 31 ? cleaned : cleaned.slice(0, 31)
}

const CR_TZ = 'America/Costa_Rica'
const CR_OFFSET = '-06:00' // ✅ CR fijo (sin DST)

/** Fecha YYYY-MM-DD → ISO con offset CR fijo */
const toOffsetISO = (yyyyMmDd, hh = '00', mm = '00', ss = '00') => {
  if (!yyyyMmDd) return null
  return `${yyyyMmDd}T${hh}:${mm}:${ss}${CR_OFFSET}`
}

const esCR = { timeZone: CR_TZ }
const fmtDMY = (val) => {
  if (!val) return '-'
  const d = new Date(val)
  if (isNaN(d)) return '-'
  return new Intl.DateTimeFormat('es-CR', { ...esCR, day:'2-digit', month:'2-digit', year:'numeric' }).format(d)
}

export default function Reportes() {
  const hoy = new Intl.DateTimeFormat('en-CA', esCR).format(new Date())

  const [mode, setMode] = useState('dia')
  const [fecha, setFecha] = useState(hoy)
  const [desde, setDesde] = useState(hoy)
  const [hasta, setHasta] = useState(hoy)

  const [loading, setLoading] = useState(false)
  const [recibidos, setRecibidos] = useState([])
  const [entregados, setEntregados] = useState([])
  const [devoluciones, setDevoluciones] = useState([])
  const [push, setPush] = useState([])
  const [almacenaje, setAlmacenaje] = useState([])
  const [exportFormat, setExportFormat] = useState('xlsx')

  // NUEVO: exportación del inventario (EN_INVENTARIO)
  const [exportingInv, setExportingInv] = useState(false)

  // auto-consulta al cambiar modo/fecha(s)
  useEffect(() => { consultar() }, [mode, fecha, desde, hasta])

  const consultar = async () => {
    setLoading(true)
    try {
      let iniISO = null, finISO = null

      if (mode === 'dia') {
        iniISO = toOffsetISO(fecha, '00', '00', '00')
        finISO = toOffsetISO(fecha, '23', '59', '59')
      } else {
        if (!desde && !hasta) { setLoading(false); return }
        iniISO = desde ? toOffsetISO(desde, '00', '00', '00') : null
        finISO = hasta ? toOffsetISO(hasta, '23', '59', '59') : null
      }

      const [rRec, rEnt, rDev, rPush, rAlm] = await Promise.all([
        api.get('/busqueda/fecha', {
          params: { tipoFecha: 'RECEPCION', ...(iniISO && { desde: iniISO }), ...(finISO && { hasta: finISO }) }
        }),
        api.get('/reportes/entregados', {
          params: { ...(iniISO && { desde: iniISO }), ...(finISO && { hasta: finISO }) }
        }),
        api.get('/reportes/devolucion', {
          params: { ...(iniISO && { desde: iniISO }), ...(finISO && { hasta: finISO }) }
        }),
        api.get('/reportes/push', {
          params: { ...(iniISO && { desde: iniISO }), ...(finISO && { hasta: finISO }) }
        }),
        api.get('/reportes/almacenaje', {
          params: { ...(iniISO && { desde: iniISO }), ...(finISO && { hasta: finISO }) }
        }),
      ])

      setRecibidos(Array.isArray(rRec.data) ? rRec.data : [])
      setEntregados(Array.isArray(rEnt.data) ? rEnt.data : [])
      setDevoluciones(Array.isArray(rDev.data) ? rDev.data : [])
      setPush(Array.isArray(rPush.data) ? rPush.data : [])
      setAlmacenaje(Array.isArray(rAlm.data) ? rAlm.data : [])
    } catch (e) {
      alert(e?.response?.data?.message || e?.message || 'Error')
    } finally { setLoading(false) }
  }

  const resetear = () => {
    setMode('dia'); setFecha(hoy); setDesde(hoy); setHasta(hoy)
    setRecibidos([]); setEntregados([]); setDevoluciones([]); setPush([]); setAlmacenaje([])
  }

  // Proyección fija para tablas/export
  const projectRows = (rows, dateKey) => {
    return (rows || []).map(r => ({
      marchamo: r?.marchamo ?? '-',
      mueble: r?.ubicacion_codigo ?? '-',
      tracking: r?.tracking_code ?? '-',
      nombre: r?.recipient_name ?? '-',
      descripcion: r?.content_description ?? '-',
      estado: r?.estado ?? '-',
      devolucion_subtipo: (r?.estado === 'DEVOLUCION') ? (r?.devolucion_subtipo ?? '-') : '-',
      telefono: r?.recipient_phone ?? '-',
      direccion: r?.recipient_address ?? '-',
      fecha: fmtDMY(r?.[dateKey]),
    }))
  }

  const autoWidth = (ws, data, headers) => {
    const wch = headers.map(h => Math.max(12, String(h).length))
    for (const row of data) {
      headers.forEach((h, i) => {
        const len = row[h] == null ? 1 : String(row[h]).length
        if (len > wch[i]) wch[i] = len
      })
    }
    ws['!cols'] = wch.map(n => ({ wch: n + 1 }))
  }

  const downloadBlob = (blob, filename) => {
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url; a.download = filename
    document.body.appendChild(a); a.click(); a.remove()
    URL.revokeObjectURL(url)
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

    if (exportFormat === 'xlsx') {
      const wb = XLSX.utils.book_new()
      addSheetFixed(wb, SHEET_TITLES.recibidos,    recibidos,    'received_at')
      addSheetFixed(wb, SHEET_TITLES.entregados,   entregados,   'delivered_at')
      addSheetFixed(wb, SHEET_TITLES.devoluciones, devoluciones, 'returned_at')
      addSheetFixed(wb, SHEET_TITLES.push,         push,         'last_state_change_at')
      addSheetFixed(wb, SHEET_TITLES.almacenaje,   almacenaje,   'last_state_change_at')
      const stamp = mode === 'dia' ? fecha : `${desde}_${hasta}`
      XLSX.writeFile(wb, `reporte_${stamp}.xlsx`, { compression: true })
      return
    }

    const all = [
      ...projectRows(recibidos,    'received_at'),
      ...projectRows(entregados,   'delivered_at'),
      ...projectRows(devoluciones, 'returned_at'),
      ...projectRows(push,         'last_state_change_at'),
      ...projectRows(almacenaje,   'last_state_change_at'),
    ]
    const headers = FIXED_KEYS.map(k => FIXED_HEADERS[k])
    const rows = all.map(d => Object.fromEntries(FIXED_KEYS.map(k => [FIXED_HEADERS[k], d[k]])))
    const ws = XLSX.utils.json_to_sheet(rows, { header: headers })
    const csv = XLSX.utils.sheet_to_csv(ws)
    const blob = new Blob([new Uint8Array([0xEF,0xBB,0xBF]), csv], { type: 'text/csv;charset=utf-8;' })
    const stamp = mode === 'dia' ? fecha : `${desde}_${hasta}`
    downloadBlob(blob, `reporte_${stamp}.csv`)
  }

  // =========================
  // NUEVO: REPORTE INVENTARIO
  // =========================
  const generarReporteInventario = async () => {
    try {
      setExportingInv(true)

      // 1) Obtener total
      const { data: cnt } = await api.get('/busqueda/inventario/count', { params: { estado: 'EN_INVENTARIO' } })
      const total = cnt?.total ?? 0
      if (!total) {
        alert('No hay paquetes en inventario para exportar.')
        return
      }

      // 2) Traer en lotes (ignora paginación de UI)
      const BATCH = 1000
      const all = []
      for (let off = 0; off < total; off += BATCH) {
        // eslint-disable-next-line no-await-in-loop
        const { data: resp } = await api.get('/busqueda/inventario', {
          params: { estado: 'EN_INVENTARIO', limit: BATCH, offset: off }
        })
        all.push(...(Array.isArray(resp) ? resp : []))
      }

      // 3) Armar Excel con las MISMAS columnas fijas del Reporte (XLSX)
      const base = projectRows(all, 'received_at')
      const withIntranet = base.map(d => ({
        ...d,
        tracking_intranet: (d.tracking && d.tracking !== '-') ? `${d.tracking},` : '-',
      }))

      const headers = FIXED_KEYS_XLSX.map(k => FIXED_HEADERS[k])
      const sheetRows = withIntranet.map(d =>
        Object.fromEntries(FIXED_KEYS_XLSX.map(k => [FIXED_HEADERS[k], d[k]]))
      )

      const ws = XLSX.utils.json_to_sheet(sheetRows, { header: headers })
      autoWidth(ws, sheetRows, headers)

      const wb = XLSX.utils.book_new()
      XLSX.utils.book_append_sheet(wb, ws, safeSheetName(SHEET_TITLES.inventario))

      const stamp = new Date().toISOString().slice(0,19).replace(/[:T]/g,'-')
      XLSX.writeFile(wb, `reporte_inventario_${stamp}.xlsx`, { compression: true })
    } catch (e) {
      alert(e?.response?.data?.message || e?.message || 'Error generando reporte de inventario')
    } finally {
      setExportingInv(false)
    }
  }

  const kpi = [
    { title: 'Recibidos', value: recibidos.length },
    { title: 'Entregados', value: entregados.length },
    { title: 'Devoluciones (incluye En tránsito)', value: devoluciones.length },
    { title: 'Push', value: push.length },
    { title: 'Almacenaje', value: almacenaje.length },
  ]

  return (
    <div>
      <h3>Reportes</h3>

      <div style={{ display:'flex', gap:8, marginBottom:12 }}>
        <button onClick={()=>setMode('dia')}   style={btnModeStyle(mode==='dia')}>Por día</button>
        <button onClick={()=>setMode('rango')} style={btnModeStyle(mode==='rango')}>Rango de fechas</button>
      </div>

      {mode === 'dia' ? (
        <div style={{ display:'flex', gap:8, alignItems:'center', marginBottom:12, flexWrap:'wrap' }}>
          <label>Fecha:
            <input type="date" value={fecha} onChange={e=>setFecha(e.target.value)} />
          </label>

          <button onClick={generarReporte} disabled={loading || totalFilas() === 0}>
            Generar reporte (Del día)
          </button>

          <button
            onClick={generarReporteInventario}
            disabled={loading || exportingInv}
            title="Genera un Excel con todos los paquetes actualmente EN_INVENTARIO (ignora rango/fecha)"
          >
            {exportingInv ? 'Generando…' : 'Generar reporte (De todo el inventario)'}
          </button>

          <select value={exportFormat} onChange={(e)=>setExportFormat(e.target.value)}>
            <option value="xlsx">Excel (.xlsx)</option>
            <option value="csv">CSV (.csv)</option>
          </select>

          <button onClick={resetear} disabled={loading}>Reiniciar</button>
        </div>
      ) : (
        <div style={{ display:'flex', gap:8, alignItems:'center', marginBottom:12, flexWrap:'wrap' }}>
          <label>Desde:
            <input type="date" value={desde} onChange={e=>setDesde(e.target.value)} />
          </label>
          <label>Hasta:
            <input type="date" value={hasta} onChange={e=>setHasta(e.target.value)} />
          </label>

          <button onClick={generarReporte} disabled={loading || totalFilas() === 0}>
            Generar reporte (Rango)
          </button>

          <button
            onClick={generarReporteInventario}
            disabled={loading || exportingInv}
            title="Genera un Excel con todos los paquetes actualmente EN_INVENTARIO (ignora rango/fecha)"
          >
            {exportingInv ? 'Generando…' : 'Generar reporte (De todo el inventario)'}
          </button>

          <select value={exportFormat} onChange={(e)=>setExportFormat(e.target.value)}>
            <option value="xlsx">Excel (.xlsx)</option>
            <option value="csv">CSV (.csv)</option>
          </select>

          <button onClick={resetear} disabled={loading}>Reiniciar</button>
        </div>
      )}

      <div style={{display:'grid', gridTemplateColumns:'repeat(5, 1fr)', gap:12, margin:'12px 0'}}>
        {kpi.map((k, i) => <Kpi key={i} title={k.title} value={k.value} />)}
      </div>

      <section style={{ marginTop: 8 }}>
        <h4>Listo para retirar tienda aeropost {mode === 'dia' ? `(${fecha})` : rangoLabel(desde, hasta)}</h4>
        <DataTable rows={recibidos} dateKey="received_at" />
      </section>

      <section style={{ marginTop: 16 }}>
        <h4>Prueba de entrega {mode === 'dia' ? `(${fecha})` : rangoLabel(desde, hasta)}</h4>
        <DataTable rows={entregados} dateKey="delivered_at" />
      </section>

      <section style={{ marginTop: 16 }}>
        <h4>En tránsito a bodegas Aeropost {mode === 'dia' ? `(${fecha})` : rangoLabel(desde, hasta)}</h4>
        <DataTable rows={devoluciones} dateKey="returned_at" />
      </section>

      <section style={{ marginTop: 16 }}>
        <h4>Push {mode === 'dia' ? `(${fecha})` : rangoLabel(desde, hasta)}</h4>
        <DataTable rows={push} dateKey="last_state_change_at" />
      </section>

      <section style={{ marginTop: 16 }}>
        <h4>Almacenaje {mode === 'dia' ? `(${fecha})` : rangoLabel(desde, hasta)}</h4>
        <DataTable rows={almacenaje} dateKey="last_state_change_at" />
      </section>
    </div>
  )

  function totalFilas(){
    return recibidos.length + entregados.length + devoluciones.length + push.length + almacenaje.length
  }
}

const btnModeStyle = (active) => ({
  padding:'8px 14px',
  border:'2px solid #28C76F',
  background: active ? '#f6fff9' : '#ffffff',
  color:'#163E7A',
  borderRadius:10,
  fontWeight:600
})

function Kpi({ title, value }){
  return (
    <div style={{background:'#ffffff', border:'1px solid rgba(22,62,122,.12)', borderRadius:12, padding:12}}>
      <div style={{opacity:.85, fontSize:12, color:'#163E7A'}}>{title}</div>
      <div style={{fontSize:28, fontWeight:700, color:'#163E7A'}}>{value ?? 0}</div>
    </div>
  )
}

function rangoLabel(desde, hasta){
  const TZ = 'America/Costa_Rica'
  const mk = (ymd) => ymd ? new Date(`${ymd}T00:00:00-06:00`) : null
  const a = mk(desde), b = mk(hasta)
  const fmt = (d) => d ? d.toLocaleDateString('es-CR', { timeZone: TZ }) : '—'
  if (a && b) return `(${fmt(a)} → ${fmt(b)})`
  if (a) return `(desde ${fmt(a)})`
  if (b) return `(hasta ${fmt(b)})`
  return ''
}

function DataTable({ rows, dateKey }){
  const data = (rows || []).map(r => ({
    marchamo: r?.marchamo ?? '-',
    mueble: r?.ubicacion_codigo ?? '-',
    tracking: r?.tracking_code ?? '-',
    nombre: r?.recipient_name ?? '-',
    descripcion: r?.content_description ?? '-',
    estado: r?.estado ?? '-',
    devolucion_subtipo: (r?.estado === 'DEVOLUCION') ? (r?.devolucion_subtipo ?? '-') : '-',
    telefono: r?.recipient_phone ?? '-',
    direccion: r?.recipient_address ?? '-',
    fecha: fmtDMY(r?.[dateKey]),
  }))

  return (
    <div style={{ maxHeight: '70vh', overflowY: 'auto' }}>
      <table style={{ width:'100%', borderCollapse:'collapse', tableLayout:'auto' }}>
        <thead>
          <tr>
            {FIXED_KEYS.map(k => (
              <th key={k} style={{ whiteSpace:'nowrap', padding:'8px 10px', textAlign:'left' }}>
                {FIXED_HEADERS[k]}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {data.length === 0 ? (
            <tr><td colSpan={FIXED_KEYS.length} style={{textAlign:'center', opacity:.7, padding:12}}>Sin resultados</td></tr>
          ) : data.map((r, i) => (
            <tr key={`${r.tracking}-${i}`} style={{ borderBottom:'1px solid rgba(0,0,0,.06)' }}>
              {FIXED_KEYS.map(col => (
                <td key={col} style={{ padding:'8px 10px', whiteSpace:'nowrap' }}>
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
