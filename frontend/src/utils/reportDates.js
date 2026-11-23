// MySQL DATETIME se publica con offset -06:00. Todas las salidas usan Costa Rica.
const DAY = 86400000
const TZ = 'America/Costa_Rica'

function dateValue(value) {
  if (!value) return null
  const raw = String(value)
  // Compatibilidad con DATETIME y fechas sin offset: son horas locales de CR.
  const normalized = /^\d{4}-\d{2}-\d{2}$/.test(raw) ? `${raw}T00:00:00-06:00`
    : /^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(\.\d+)?$/.test(raw)
      ? `${raw.replace(' ', 'T')}-06:00` : raw
  const date = new Date(normalized)
  return Number.isNaN(date.getTime()) ? null : date
}

export function formatReportDate(value, includeSeconds = false) {
  const date = dateValue(value)
  if (!date) return '—'
  return new Intl.DateTimeFormat('es-CR', {
    timeZone: TZ, year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
    ...(includeSeconds ? { second: '2-digit' } : {}),
  }).format(date)
}

export function excelDate(value) {
  const date = dateValue(value)
  if (!date) return null
  // Excel guarda la hora de pared, sin zona horaria. No depende de la PC del usuario.
  return { t: 'n', v: (date.getTime() - 6 * 3600000) / DAY + 25569,
    z: 'dd/mm/yyyy hh:mm' }
}

export function realDeliveryDays(received, delivered) {
  const start = dateValue(received), end = dateValue(delivered)
  if (!start || !end || end < start) return null
  return Math.round((end - start) / DAY * 100) / 100
}

export function reportDateFields(row, excel = false) {
  const format = excel ? excelDate : formatReportDate
  return {
    received_at: format(row?.received_at),
    registered_at: format(row?.registered_at),
    delivered_at: format(row?.delivered_at),
    delivery_days: realDeliveryDays(row?.received_at, row?.delivered_at),
  }
}
