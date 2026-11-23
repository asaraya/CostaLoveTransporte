import { excelDate, formatReportDate, realDeliveryDays } from './reportDates.js'
import * as XLSX from 'xlsx'

export function periodRange(month, period) {
  if (!/^\d{4}-\d{2}$/.test(month)) return null
  const [year, number] = month.split('-').map(Number)
  if (number < 1 || number > 12 || year < 1000 || year > 9998) return null
  const last = new Date(Date.UTC(year, number, 0)).getUTCDate()
  return { desde: `${month}-${period === 'segunda' ? '16' : '01'}`,
    hasta: `${month}-${period === 'primera' ? '15' : last}` }
}

export function courierSummary(rows) {
  const groups = new Map()
  for (const row of rows) {
    const key = row.mensajero_id ?? 0
    if (!groups.has(key)) groups.set(key, { id: key, mensajero: row.mensajero, paquetes: 0 })
    groups.get(key).paquetes++
  }
  return [...groups.values()]
}

export function courierRows(rows, excel = false) {
  const date = excel ? excelDate : formatReportDate
  return rows.map(r => ({
    'ID mensajero': r.mensajero_id ?? '', Mensajero: r.mensajero,
    Tracking: r.tracking_code, Cliente: r.recipient_name ?? '',
    Teléfono: r.recipient_phone ?? '', Dirección: r.recipient_address ?? '',
    Distrito: r.distrito ?? '', Marchamo: r.marchamo ?? '',
    'Fecha hoja de ruta': r.fecha_hoja_ruta ? (excel ? excelDate(r.fecha_hoja_ruta) : r.fecha_hoja_ruta) : '',
    'Recepción real': date(r.received_at), 'Ingreso al sistema': date(r.registered_at),
    'Entrega real': date(r.delivered_at),
    'Días reales hasta entrega': realDeliveryDays(r.received_at, r.delivered_at),
    Estado: 'Prueba de entrega',
  }))
}

export function courierWorkbook(rows) {
  const wb = XLSX.utils.book_new()
  const summary = courierSummary(rows).map(r => ({
    'ID mensajero': r.id || '', Mensajero: r.mensajero, 'Paquetes entregados': r.paquetes,
  }))
  summary.push({ 'ID mensajero': '', Mensajero: 'TOTAL', 'Paquetes entregados': rows.length })
  const resumen = XLSX.utils.json_to_sheet(summary)
  resumen['!cols'] = [{ wch: 16 }, { wch: 35 }, { wch: 24 }]
  XLSX.utils.book_append_sheet(wb, resumen, 'Resumen por mensajero')

  // Agrupar por identidad, no por nombre: dos mensajeros pueden llamarse igual.
  const groups = new Map()
  for (const row of rows) {
    const id = row.mensajero_id ?? 0
    if (!groups.has(id)) groups.set(id, [])
    groups.get(id).push(row)
  }
  const used = new Set(wb.SheetNames.map(name => name.toLowerCase()))
  for (const packages of groups.values()) {
    const base = Array.from(String(packages[0].mensajero || 'Sin mensajero'))
      .map(c => c.charCodeAt(0) < 32 || '\\/:?*[]'.includes(c) ? ' ' : c)
      .join('').trim().replace(/^'+|'+$/g, '') || 'Mensajero'
    let name = base.slice(0, 31).replace(/'+$/g, '')
    let n = 2
    while (used.has(name.toLowerCase())) {
      const suffix = ` (${n++})`
      name = base.slice(0, 31 - suffix.length) + suffix
    }
    used.add(name.toLowerCase())
    const details = courierRows(packages, true)
    const sheet = XLSX.utils.json_to_sheet(details)
    sheet['!cols'] = Object.keys(details[0]).map(key => ({ wch: key === 'Dirección' ? 45 : 26 }))
    sheet['!autofilter'] = { ref: sheet['!ref'] }
    XLSX.utils.book_append_sheet(wb, sheet, name)
  }
  return wb
}
