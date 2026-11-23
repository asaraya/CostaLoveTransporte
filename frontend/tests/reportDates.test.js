import test from 'node:test'
import assert from 'node:assert/strict'
import * as XLSX from 'xlsx'
import { excelDate, formatReportDate, realDeliveryDays, reportDateFields } from '../src/utils/reportDates.js'

test('el ingreso al sistema no altera el tiempo real de entrega', () => {
  const row = { received_at: '2026-10-20T12:00:00-06:00',
    registered_at: '2026-10-22T14:35:38-06:00', delivered_at: '2026-10-21T12:00:00-06:00' }
  assert.equal(reportDateFields(row).delivery_days, 1)
  assert.match(reportDateFields(row).registered_at, /22\/10\/2026/)
  assert.deepEqual(Object.keys(reportDateFields(row)), ['received_at', 'registered_at', 'delivered_at', 'delivery_days'])
})

test('el XLSX conserva fechas numéricas y permite restarlas tras exportar y abrir', () => {
  const data = reportDateFields({ received_at: '2026-10-20T00:00:00-06:00',
    registered_at: '2026-10-22T14:35:38-06:00', delivered_at: '2026-10-21T12:00:00-06:00' }, true)
  const wb = XLSX.utils.book_new()
  XLSX.utils.book_append_sheet(wb, XLSX.utils.json_to_sheet([data]), 'Reporte')
  const read = XLSX.read(XLSX.write(wb, { type: 'buffer', bookType: 'xlsx' }), { type: 'buffer', cellNF: true })
  const ws = read.Sheets.Reporte
  assert.equal(ws.A2.t, 'n')
  assert.equal(ws.B2.t, 'n')
  assert.equal(ws.C2.v - ws.A2.v, 1.5)
  assert.equal(ws.D2.v, 1.5)
  assert.equal(ws.A2.z, 'dd/mm/yyyy hh:mm')
  assert.equal(XLSX.utils.format_cell(ws.A2), '20/10/2026 00:00')
})

test('la medianoche de Costa Rica no cambia según la zona del navegador', () => {
  assert.equal(excelDate('2026-10-20T06:00:00Z').v, excelDate('2026-10-20').v)
  assert.match(formatReportDate('2026-10-20T05:59:00Z'), /19\/10\/2026/)
  assert.match(formatReportDate('2026-10-20T06:00:00Z'), /20\/10\/2026/)
})

test('fechas ausentes o inválidas no se convierten en cero ni en una entrega', () => {
  assert.equal(excelDate(null), null)
  assert.equal(excelDate('invalid'), null)
  assert.equal(reportDateFields({}).delivery_days, null)
  assert.equal(realDeliveryDays('2026-10-21', '2026-10-20'), null)
  assert.equal(realDeliveryDays('2026-10-20', '2026-10-20'), 0)
})
