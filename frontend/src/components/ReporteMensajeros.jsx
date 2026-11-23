import { useEffect, useState } from 'react'
import * as XLSX from 'xlsx'
import { api } from '../api'
import { courierRows, courierSummary, courierWorkbook, periodRange } from '../utils/courierReport'

export default function ReporteMensajeros() {
  const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Costa_Rica' }).format(new Date())
  const [month, setMonth] = useState(today.slice(0, 7))
  const [period, setPeriod] = useState('mes')
  const [range, setRange] = useState(() => periodRange(today.slice(0, 7), 'mes'))
  const [courier, setCourier] = useState('')
  const [options, setOptions] = useState([])
  const [result, setResult] = useState(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  useEffect(() => {
    let active = true
    api.get('/reportes/mensajeros/opciones').then(({ data }) => { if (active) setOptions(data) })
      .catch(() => { if (active) setError('No se pudo cargar la lista de mensajeros. Puede consultar todos o volver a abrir Reportes.') })
    return () => { active = false }
  }, [])
  const changePeriod = (nextPeriod, nextMonth) => {
    setPeriod(nextPeriod); setMonth(nextMonth); setResult(null)
    if (nextPeriod !== 'personalizado') setRange(periodRange(nextMonth, nextPeriod) || { desde: '', hasta: '' })
  }
  const consultar = async e => {
    e.preventDefault()
    if (!range.desde || !range.hasta || range.desde > range.hasta) { setError('Revise el rango de fechas.'); return }
    setBusy(true); setError(''); setResult(null)
    try {
      const params = { ...range, ...(courier !== '' ? { mensajeroId: courier } : {}) }
      const { data } = await api.get('/reportes/mensajeros', { params })
      setResult({ rows: data, ...range })
    } catch (err) { setError(err?.response?.data?.message || 'No se pudo consultar el reporte de mensajeros.') }
    finally { setBusy(false) }
  }
  const summary = courierSummary(result?.rows || [])
  const detail = courierRows(result?.rows || [])
  const descargar = () => {
    if (!result?.rows.length) return
    const wb = courierWorkbook(result.rows)
    XLSX.writeFile(wb, `entregas_mensajeros_${result.desde}_${result.hasta}.xlsx`, { compression: true })
  }
  return <section style={{ border: '1px solid #cbd5e1', borderRadius: 12, padding: 16, marginBottom: 24 }}>
    <h3 style={{ marginTop: 0 }}>Entregas por mensajero</h3>
    <form onSubmit={consultar}>
      <fieldset disabled={busy} style={{ border: 0, padding: 0, display: 'flex', flexWrap: 'wrap', gap: 12, alignItems: 'end' }}>
        <label>Período<br /><select aria-label="Período" value={period} onChange={e => changePeriod(e.target.value, month)}>
          <option value="mes">Mes completo</option><option value="primera">Primera quincena</option>
          <option value="segunda">Segunda quincena</option><option value="personalizado">Rango personalizado</option>
        </select></label>
        {period !== 'personalizado' && <label>Mes<br /><input type="month" required value={month} onChange={e => changePeriod(period, e.target.value)} /></label>}
        <label>Desde<br /><input type="date" required value={range.desde} onChange={e => { setPeriod('personalizado'); setRange({ ...range, desde: e.target.value }); setResult(null) }} /></label>
        <label>Hasta<br /><input type="date" required value={range.hasta} onChange={e => { setPeriod('personalizado'); setRange({ ...range, hasta: e.target.value }); setResult(null) }} /></label>
        <label>Mensajero<br /><select aria-label="Mensajero" value={courier} onChange={e => { setCourier(e.target.value); setResult(null) }}>
          <option value="">Todos los mensajeros</option><option value="0">Sin mensajero registrado</option>
          {options.map(u => <option key={u.id} value={u.id}>{u.nombre}{!u.active ? ' (inactivo)' : ''} · #{u.id}</option>)}
        </select></label>
        <button type="submit">{busy ? 'Consultando…' : 'Consultar entregas'}</button>
        <button type="button" onClick={descargar} disabled={!result?.rows.length}>Descargar Excel de mensajeros</button>
      </fieldset>
    </form>
    {error && <p role="alert" style={{ color: '#b91c1c' }}>{error}</p>}
    {result && <>
      <p><strong>Total: {result.rows.length} paquetes entregados</strong> · {result.desde} al {result.hasta}</p>
      {!result.rows.length ? <p>Sin entregas para los filtros seleccionados.</p> : <>
        <ul>{summary.map(s => <li key={s.id}>{s.mensajero}{s.id ? ` (#${s.id})` : ''}: <strong>{s.paquetes}</strong></li>)}</ul>
        
        <div style={{ overflow: 'auto', maxHeight: 480 }}><table style={{ borderCollapse: 'collapse', width: '100%' }}>
          <thead><tr>{Object.keys(detail[0]).map(k => <th key={k} style={{ whiteSpace: 'nowrap', padding: 8 }}>{k}</th>)}</tr></thead>
          <tbody>{detail.map((r, i) => <tr key={result.rows[i].id}>{Object.entries(r).map(([k, v]) => <td key={k} style={{ padding: 8, whiteSpace: 'nowrap' }}>{v ?? '—'}</td>)}</tr>)}</tbody>
        </table></div>
      </>}
    </>}
  </section>
}
