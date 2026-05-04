import { useEffect, useMemo, useState } from 'react'
import { api, toastErr, toastOk } from '../api'
import * as XLSX from 'xlsx'

const CR_TZ = 'America/Costa_Rica'

function todayCR() {
  return new Intl.DateTimeFormat('en-CA', { timeZone: CR_TZ }).format(new Date())
}

function parseTrackings(raw) {
  if (!raw) return []

  const tokens = raw
    .split(/[\s,;]+/g)
    .map(s => s.trim().toUpperCase())
    .filter(Boolean)

  const seen = new Set()
  const out = []

  for (const t of tokens) {
    if (seen.has(t)) continue
    seen.add(t)
    out.push(t)
  }

  return out
}

function fmtDateTime(value) {
  if (!value) return '-'
  try {
    return new Date(value).toLocaleString('es-CR', { timeZone: CR_TZ })
  } catch {
    return String(value)
  }
}

function safeSheetName(name) {
  const s = String(name || 'Hoja').trim() || 'Hoja'
  return s.slice(0, 31).replace(/[\[\]\*\/\\\?\:]/g, '_')
}

function autoWidth(ws, rows, headers) {
  const colWidths = headers.map(h => ({ wch: Math.max(10, String(h).length + 2) }))
  for (const r of rows) {
    headers.forEach((h, i) => {
      const v = r?.[h]
      const len = v == null ? 0 : String(v).length
      colWidths[i].wch = Math.max(colWidths[i].wch, Math.min(70, len + 2))
    })
  }
  ws['!cols'] = colWidths
}

function makeExcelRows(paquetes) {
  const rows = Array.isArray(paquetes) ? paquetes : []
  const total = rows.length

  return rows.map((p, idx) => ({
    N: total - idx,
    TRACK: p?.tracking_code ?? '-',
    Cliente: p?.cliente ?? '-',
    CEL: p?.cel ?? '-',
    'THIRDPARTY ADDRESS': p?.thirdparty_address ?? '-',
    ESTATUS: '',
    FIRMA: '',
  }))
}

function HojaRutaTable({ paquetes, editing, onRemove }) {
  const rows = Array.isArray(paquetes) ? paquetes : []
  const total = rows.length

  return (
    <div style={{ maxHeight: 420, overflow: 'auto', border: '1px solid rgba(22,62,122,.15)', borderRadius: 10 }}>
      <table style={{ width: '100%', borderCollapse: 'collapse' }}>
        <thead>
          <tr>
            {editing && <th style={th}>Quitar</th>}
            <th style={th}>N</th>
            <th style={th}>TRACK</th>
            <th style={th}>Cliente</th>
            <th style={th}>CEL</th>
            <th style={th}>THIRDPARTY ADDRESS</th>
            <th style={th}>ESTATUS</th>
            <th style={th}>FIRMA</th>
          </tr>
        </thead>
        <tbody>
          {rows.length === 0 ? (
            <tr>
              <td colSpan={editing ? 8 : 7} style={{ padding: 12, textAlign: 'center', opacity: .7 }}>
                Sin paquetes. Al guardar, esta hoja se eliminará por completo.
              </td>
            </tr>
          ) : rows.map((p, idx) => (
            <tr key={p.id ?? p.tracking_code ?? idx} style={{ borderBottom: '1px solid rgba(0,0,0,.06)' }}>
              {editing && (
                <td style={tdCenter}>
                  <button
                    type="button"
                    onClick={() => onRemove?.(idx)}
                    title="Quitar paquete de esta hoja de ruta"
                    style={dangerBtn}
                  >
                    ×
                  </button>
                </td>
              )}
              <td style={tdCenter}>{total - idx}</td>
              <td style={td}>{p?.tracking_code ?? '-'}</td>
              <td style={td}>{p?.cliente ?? '-'}</td>
              <td style={td}>{p?.cel ?? '-'}</td>
              <td style={td}>{p?.thirdparty_address ?? '-'}</td>
              <td style={td}></td>
              <td style={td}></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

export default function HojaRuta() {
  const hoy = todayCR()

  const [tab, setTab] = useState('generar')
  const [transportistas, setTransportistas] = useState([])

  const [fechaGenerar, setFechaGenerar] = useState(hoy)
  const [transportistaId, setTransportistaId] = useState('')
  const [rawTrackings, setRawTrackings] = useState('')
  const trackings = useMemo(() => parseTrackings(rawTrackings), [rawTrackings])
  const [saving, setSaving] = useState(false)

  const [fechaConsulta, setFechaConsulta] = useState(hoy)
  const [hojas, setHojas] = useState([])
  const [loadingConsulta, setLoadingConsulta] = useState(false)

  const [editId, setEditId] = useState(null)
  const [editRows, setEditRows] = useState([])
  const [savingEdit, setSavingEdit] = useState(false)
  const [deletingId, setDeletingId] = useState(null)

  useEffect(() => {
    cargarTransportistas()
  }, [])

  useEffect(() => {
    if (tab === 'consultar') consultarHojas()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab])

  const cargarTransportistas = async () => {
    try {
      const { data } = await api.get('/hojas-ruta/transportistas')
      const arr = Array.isArray(data) ? data : []
      setTransportistas(arr)
      if (arr.length && !transportistaId) setTransportistaId(String(arr[0].id))
    } catch (e) {
      toastErr(e)
    }
  }

  const consultarHojas = async () => {
    if (!fechaConsulta) return
    setLoadingConsulta(true)
    try {
      const { data } = await api.get('/hojas-ruta', { params: { fecha: fechaConsulta } })
      setHojas(Array.isArray(data?.hojas) ? data.hojas : [])
      setEditId(null)
      setEditRows([])
    } catch (e) {
      toastErr(e)
      setHojas([])
    } finally {
      setLoadingConsulta(false)
    }
  }

  const guardarHoja = async () => {
    if (!fechaGenerar) {
      toastErr({ message: 'Seleccione la fecha de la hoja de ruta' })
      return
    }
    if (!transportistaId) {
      toastErr({ message: 'Seleccione un transportista' })
      return
    }
    if (!trackings.length) {
      toastErr({ message: 'Ingrese al menos un tracking' })
      return
    }

    setSaving(true)
    try {
      const { data } = await api.post('/hojas-ruta', {
        fecha: fechaGenerar,
        transportistaId: Number(transportistaId),
        trackings,
      })

      toastOk(`Hoja de ruta guardada. Paquetes: ${data?.total ?? trackings.length}`)
      setRawTrackings('')
      setFechaConsulta(fechaGenerar)
      setTab('consultar')
      setTimeout(() => consultarHojas(), 0)
    } catch (e) {
      toastErr(e)
    } finally {
      setSaving(false)
    }
  }

  const empezarEditar = (hoja) => {
    setEditId(hoja.id)
    setEditRows(Array.isArray(hoja.paquetes) ? hoja.paquetes.map(x => ({ ...x })) : [])
  }

  const cancelarEditar = () => {
    setEditId(null)
    setEditRows([])
  }

  const quitarEditRow = (idx) => {
    setEditRows(prev => prev.filter((_, i) => i !== idx))
  }

  const guardarEdicion = async (hojaId) => {
    const nuevosTrackings = editRows
      .map(r => String(r?.tracking_code ?? '').trim().toUpperCase())
      .filter(Boolean)

    if (nuevosTrackings.length === 0) {
      const ok = confirm('Esta hoja quedó sin paquetes. Al guardar se eliminará por completo. ¿Continuar?')
      if (!ok) return
    }

    setSavingEdit(true)
    try {
      const { data } = await api.put(`/hojas-ruta/${encodeURIComponent(hojaId)}`, {
        trackings: nuevosTrackings,
      })

      if (data?.deleted) {
        toastOk('Hoja de ruta eliminada porque quedó sin paquetes')
      } else {
        toastOk('Hoja de ruta actualizada')
      }

      setEditId(null)
      setEditRows([])
      await consultarHojas()
    } catch (e) {
      toastErr(e)
    } finally {
      setSavingEdit(false)
    }
  }

  const eliminarHoja = async (hoja) => {
    if (!hoja?.id) return

    const total = Array.isArray(hoja.paquetes) ? hoja.paquetes.length : 0
    const msg =
      `¿Eliminar la hoja de ruta #${hoja.id} de ${hoja.transportista ?? 'este transportista'}?\n\n` +
      `Se quitarán ${total} paquete(s) de esta hoja de ruta.\n` +
      `Esto NO elimina los paquetes del sistema ni cambia su estado.`

    if (!confirm(msg)) return

    setDeletingId(hoja.id)
    try {
      await api.delete(`/hojas-ruta/${encodeURIComponent(hoja.id)}`)
      toastOk('Hoja de ruta eliminada')

      if (editId === hoja.id) {
        setEditId(null)
        setEditRows([])
      }

      await consultarHojas()
    } catch (e) {
      toastErr(e)
    } finally {
      setDeletingId(null)
    }
  }

  const descargarExcel = () => {
    if (!hojas.length) {
      toastErr({ message: 'No hay hojas de ruta para descargar' })
      return
    }

    const wb = XLSX.utils.book_new()
    const usedNames = new Set()

    for (const hoja of hojas) {
      const rows = makeExcelRows(hoja.paquetes)
      const headers = ['N', 'TRACK', 'Cliente', 'CEL', 'THIRDPARTY ADDRESS', 'ESTATUS', 'FIRMA']
      const ws = XLSX.utils.json_to_sheet(rows, { header: headers })
      autoWidth(ws, rows, headers)

      let sheetName = safeSheetName(`${hoja.transportista || 'Transportista'} ${hoja.id}`)
      let suffix = 2
      while (usedNames.has(sheetName)) {
        sheetName = safeSheetName(`${hoja.transportista || 'Transportista'} ${hoja.id} ${suffix++}`)
      }
      usedNames.add(sheetName)

      XLSX.utils.book_append_sheet(wb, ws, sheetName)
    }

    XLSX.writeFile(wb, `hoja_de_ruta_${fechaConsulta}.xlsx`, { compression: true })
  }

  return (
    <div className="page">
      <h2 style={{ marginBottom: 12 }}>Hoja de Ruta</h2>

      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 14 }}>
        <button
          type="button"
          className={`toggle ${tab === 'generar' ? 'is-selected' : ''}`}
          aria-pressed={tab === 'generar'}
          onClick={() => setTab('generar')}
        >
          Generar hoja de ruta
        </button>
        <button
          type="button"
          className={`toggle ${tab === 'consultar' ? 'is-selected' : ''}`}
          aria-pressed={tab === 'consultar'}
          onClick={() => setTab('consultar')}
        >
          Consultar hoja de ruta
        </button>
      </div>

      {tab === 'generar' && (
        <section style={card}>
          <h3 style={{ marginTop: 0 }}>Generar hoja de ruta</h3>

          <div style={{ display: 'grid', gridTemplateColumns: '220px 1fr', gap: 12, marginBottom: 12 }}>
            <label style={{ display: 'grid', gap: 6 }}>
              <span>Fecha:</span>
              <input
                type="date"
                value={fechaGenerar}
                onChange={e => setFechaGenerar(e.target.value)}
              />
            </label>

            <label style={{ display: 'grid', gap: 6 }}>
              <span>Transportista:</span>
              <select
                value={transportistaId}
                onChange={e => setTransportistaId(e.target.value)}
              >
                {transportistas.length === 0 && <option value="">Sin transportistas</option>}
                {transportistas.map(t => (
                  <option key={t.id} value={t.id}>{t.nombre ?? t.full_name ?? t.username}</option>
                ))}
              </select>
            </label>
          </div>

          <label style={{ display: 'grid', gap: 6 }}>
            <span>Paquetes por número de envío / tracking:</span>
            <textarea
              rows={10}
              value={rawTrackings}
              onChange={e => setRawTrackings(e.target.value)}
              placeholder="CR1234567890&#10;HZCR1234567890&#10;..."
              style={{ width: '100%' }}
            />
          </label>

          <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap', marginTop: 10 }}>
            <span style={{ opacity: .8 }}>{trackings.length} tracking(s) listo(s)</span>
            <button type="button" onClick={guardarHoja} disabled={saving || !trackings.length || !transportistaId}>
              {saving ? 'Guardando…' : 'Guardar hoja de ruta'}
            </button>
            <button type="button" onClick={() => setRawTrackings('')} disabled={saving}>
              Limpiar
            </button>
          </div>
        </section>
      )}

      {tab === 'consultar' && (
        <section style={card}>
          <h3 style={{ marginTop: 0 }}>Consultar hoja de ruta</h3>

          <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap', marginBottom: 12 }}>
            <label>
              Fecha:{' '}
              <input
                type="date"
                value={fechaConsulta}
                onChange={e => setFechaConsulta(e.target.value)}
              />
            </label>
            <button type="button" onClick={consultarHojas} disabled={loadingConsulta || !fechaConsulta}>
              {loadingConsulta ? 'Consultando…' : 'Consultar'}
            </button>
            <button type="button" onClick={descargarExcel} disabled={loadingConsulta || hojas.length === 0}>
              Descargar Excel
            </button>
          </div>

          {hojas.length === 0 ? (
            <div style={{ padding: 14, textAlign: 'center', opacity: .7 }}>
              No hay hojas de ruta para la fecha seleccionada.
            </div>
          ) : hojas.map(hoja => {
            const editing = editId === hoja.id
            const paquetes = editing ? editRows : (hoja.paquetes || [])
            const deleting = deletingId === hoja.id

            return (
              <div key={hoja.id} style={{ marginBottom: 18, border: '1px solid rgba(22,62,122,.16)', borderRadius: 12, padding: 12 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap', marginBottom: 8 }}>
                  <h4 style={{ margin: 0 }}>
                    {hoja.transportista} · Hoja #{hoja.id} · {paquetes.length} paquete(s)
                  </h4>
                  <span style={{ opacity: .75, fontSize: 12 }}>
                    Creada por {hoja.created_by ?? '-'} · {fmtDateTime(hoja.created_at)}
                  </span>

                  <div style={{ marginLeft: 'auto', display: 'flex', gap: 8 }}>
                    {!editing ? (
                      <>
                        <button type="button" onClick={() => empezarEditar(hoja)} disabled={deleting}>
                          Editar
                        </button>
                        <button
                          type="button"
                          onClick={() => eliminarHoja(hoja)}
                          disabled={deleting}
                          style={deleteSheetBtn}
                          title="Eliminar esta hoja de ruta completa"
                        >
                          {deleting ? 'Eliminando…' : 'Eliminar hoja'}
                        </button>
                      </>
                    ) : (
                      <>
                        <button type="button" onClick={() => guardarEdicion(hoja.id)} disabled={savingEdit}>
                          {savingEdit ? 'Guardando…' : (editRows.length === 0 ? 'Eliminar hoja vacía' : 'Guardar')}
                        </button>
                        <button type="button" onClick={cancelarEditar} disabled={savingEdit}>
                          Cancelar
                        </button>
                      </>
                    )}
                  </div>
                </div>

                <HojaRutaTable
                  paquetes={paquetes}
                  editing={editing}
                  onRemove={quitarEditRow}
                />
              </div>
            )
          })}
        </section>
      )}
    </div>
  )
}

const card = {
  border: '1px solid rgba(22,62,122,.18)',
  borderRadius: 12,
  padding: 14,
  background: '#fff',
}

const th = {
  textAlign: 'left',
  padding: '8px 10px',
  background: '#f7f9fc',
  borderBottom: '1px solid rgba(22,62,122,.15)',
  whiteSpace: 'nowrap',
}

const td = {
  padding: '8px 10px',
  verticalAlign: 'top',
}

const tdCenter = {
  ...td,
  textAlign: 'center',
}

const dangerBtn = {
  border: '1px solid #c62828',
  background: '#fff',
  color: '#c62828',
  borderRadius: 8,
  padding: '4px 10px',
  fontWeight: 800,
  cursor: 'pointer',
}

const deleteSheetBtn = {
  border: '1px solid #c62828',
  background: '#fff5f5',
  color: '#c62828',
  borderRadius: 8,
  padding: '6px 10px',
  fontWeight: 700,
  cursor: 'pointer',
}