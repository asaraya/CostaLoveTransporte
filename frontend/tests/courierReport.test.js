import test from 'node:test'
import assert from 'node:assert/strict'
import * as XLSX from 'xlsx'
import { periodRange, courierSummary, courierRows, courierWorkbook } from '../src/utils/courierReport.js'

test('quincenas, febrero bisiesto y límites de mes', () => {
  assert.deepEqual(periodRange('2026-10', 'primera'), { desde: '2026-10-01', hasta: '2026-10-15' })
  assert.deepEqual(periodRange('2026-10', 'segunda'), { desde: '2026-10-16', hasta: '2026-10-31' })
  assert.equal(periodRange('2028-02', 'mes').hasta, '2028-02-29')
  assert.equal(periodRange('2026-02', 'segunda').hasta, '2026-02-28')
  assert.equal(periodRange('', 'mes'), null)
})

test('mensajeros homónimos no se mezclan y los paquetes sin asignación quedan identificados', () => {
  const summary = courierSummary([{ mensajero_id: 1, mensajero: 'Andrés' },
    { mensajero_id: 2, mensajero: 'Andrés' }, { mensajero_id: 1, mensajero: 'Andrés' },
    { mensajero_id: null, mensajero: 'Sin mensajero' }])
  assert.deepEqual(summary.map(s => s.paquetes), [2, 1, 1])
})

test('el detalle Excel conserva fechas calculables y no usa el ingreso al sistema para duración', () => {
  const rows = courierRows([{ mensajero_id: 1, mensajero: 'Ana', tracking_code: 'LM1',
    received_at: '2026-10-20T00:00:00-06:00', registered_at: '2026-10-22T10:00:00-06:00',
    delivered_at: '2026-10-21T12:00:00-06:00' }], true)
  const wb = XLSX.utils.book_new()
  XLSX.utils.book_append_sheet(wb, XLSX.utils.json_to_sheet(rows), 'Entregas')
  const reopened = XLSX.read(XLSX.write(wb, { type: 'buffer', bookType: 'xlsx' }), { type: 'buffer' })
  const [row] = XLSX.utils.sheet_to_json(reopened.Sheets.Entregas)
  assert.equal(row['Entrega real'] - row['Recepción real'], 1.5)
  assert.equal(row['Días reales hasta entrega'], 1.5)
  assert.equal(row['Fecha hoja de ruta'], '')
})

test('Excel mantiene el resumen primero y separa los paquetes por ID de mensajero', () => {
  const rows = [
    { mensajero_id: 1, mensajero: 'Ana', tracking_code: 'LM1' },
    { mensajero_id: 2, mensajero: 'ANA', tracking_code: 'LM2' },
    { mensajero_id: 1, mensajero: 'Ana', tracking_code: 'LM3' },
    { mensajero_id: null, mensajero: 'Sin mensajero', tracking_code: 'LM4' },
    { mensajero_id: 3, mensajero: "'Nombre / largo: con caracteres [especiales] y más de 31 letras'", tracking_code: 'LM5' },
    { mensajero_id: 4, mensajero: 'Resumen por mensajero', tracking_code: 'LM6' },
  ]
  const wb = courierWorkbook(rows)
  const read = XLSX.read(XLSX.write(wb, { type: 'buffer', bookType: 'xlsx' }), { type: 'buffer' })
  assert.equal(read.SheetNames[0], 'Resumen por mensajero')
  assert.equal(read.SheetNames.length, 6)
  assert.equal(new Set(read.SheetNames.map(n => n.toLowerCase())).size, 6)
  assert.ok(read.SheetNames.every(n => n.length <= 31 && [...n].every(c => !'\\/:?*[]'.includes(c))))
  const details = read.SheetNames.slice(1).map(name => XLSX.utils.sheet_to_json(read.Sheets[name]))
  assert.deepEqual(details.map(group => group.map(r => r.Tracking)), [['LM1', 'LM3'], ['LM2'], ['LM4'], ['LM5'], ['LM6']])
  const summary = XLSX.utils.sheet_to_json(read.Sheets[read.SheetNames[0]])
  assert.equal(summary.at(-1)['Paquetes entregados'], rows.length)
  assert.deepEqual(summary.slice(0, -1).map(r => r['Paquetes entregados']), details.map(group => group.length))
})
