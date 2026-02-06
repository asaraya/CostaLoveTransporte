import { useEffect, useState } from 'react'
import { api, toastErr } from '../api'
import * as XLSX from 'xlsx'

/* columnas a ocultar (compat) */
const EXCLUDED_COLUMNS = new Set([
  'id',
  'status_externo', 'status_externo_at',
  'saco_id',
  'distrito_id',
])

/* === COLUMNA FIJA Y ORDEN CONSISTENTE === */
const FIXED_COLUMNS = [
  'marchamo',
  'tracking_code',
  'recipient_name',
  'recipient_phone',
  'recipient_address',
  'estado',
  'devolucion_subtipo',
  'distrito_nombre',
  'received_at',
  'delivered_at',
  'returned_at',
  'last_state_change_at',
  'merchandise_value',
  'content_description',
  'observaciones',
  'cambio_en_sistema_por',
  'responsable_consolidado',
]

// Encabezados en español
const HEADERS_ES = {
  marchamo: 'Marchamo',
  tracking_code: 'Tracking',
  recipient_name: 'Destinatario',
  recipient_phone: 'Teléfono',
  recipient_address: 'Dirección',
  estado: 'Estado',
  devolucion_subtipo: 'Subtipo devolución',
  distrito_nombre: 'Distrito',
  received_at: 'Recepción',
  delivered_at: 'Entrega',
  returned_at: 'Devolución',
  last_state_change_at: 'Último cambio',
  merchandise_value: 'Valor (USD)',
  content_description: 'Contenido',
  observaciones: 'Observaciones',
  cambio_en_sistema_por: 'Último cambio por',
  responsable_consolidado: 'Responsable (Excel)',
}

const SEARCH_TYPES = [
  { key: 'todos',     label: 'Todos' },
  { key: 'marchamo',  label: 'Marchamo' },
  { key: 'tracking',  label: 'Número de envío (tracking)' },
  { key: 'nombre',    label: 'Nombre destinatario' },
  { key: 'direccion', label: 'Dirección' },
  { key: 'distrito',  label: 'Distrito' },
  { key: 'vigencia',  label: 'Vigencia' },
]

// ✅ Estados (códigos) -> nombres EXACTOS que pediste + orden correcto
const ESTADOS_UI = [
  { key: 'ENTREGADO_A_TRANSPORTISTA_LOCAL', label: 'Entregado a transportista local' },
  { key: 'NO_ENTREGADO_CONSIGNATARIO_DISPONIBLE', label: 'No entregado - Consignatario no disponible' },
  { key: 'ENTREGADO_A_TRANSPORTISTA_LOCAL_2DO_INTENTO', label: 'Entregado a transportista local - 2do intento' },
  { key: 'NO_ENTREGABLE', label: 'No entregable - Retornado a oficina local' },
  { key: 'TODOS', label: 'Todos' },
]

const ESTADO_LABEL = Object.fromEntries(ESTADOS_UI.map(x => [x.key, x.label]))

// Subfiltros para NO_ENTREGABLE (subestados)
const DEV_SUBS = [
  { key: 'TODOS',         label: 'Todos' },
  { key: 'FUERA_DE_RUTA', label: 'Fuera de ruta' },
  { key: 'VENCIDOS',      label: 'Vencidos' },
  { key: 'DOS_INTENTOS',  label: 'Dos intentos' },
]

const DEV_SUB_LABEL = Object.fromEntries(DEV_SUBS.map(x => [x.key, x.label]))

/* Helper de TZ local (CR) para display */
const fmtDateTime = (iso) => {
  if (!iso) return ''
  const d = new Date(iso)
  return d.toLocaleString('es-CR', { timeZone: 'America/Costa_Rica' })
}

function fmtCell(val) {
  if (val == null) return ''
  if (typeof val === 'string' && /\d{4}-\d{2}-\d{2}T/.test(val)) return fmtDateTime(val)
  if (typeof val === 'number') return String(val)
  return String(val)
}

function fmtEstado(code) {
  if (!code) return ''
  return ESTADO_LABEL[code] || code
}

function fmtDevSub(estadoCode, subCode) {
  if (estadoCode !== 'NO_ENTREGABLE') return '-'
  if (!subCode) return '-'
  return DEV_SUB_LABEL[subCode] || subCode
}

/* === Helpers para exportación === */
const prettyHeader = (key) => HEADERS_ES[key] || key
const autoWidth = (ws, rows, headers) => {
  const wch = headers.map(h => Math.max(12, String(h).length))
  for (const r of rows) {
    headers.forEach((h, i) => {
      const len = r[h] == null ? 1 : String(r[h]).length
      if (len > wch[i]) wch[i] = len
    })
  }
  ws['!cols'] = wch.map(n => ({ wch: n + 1 }))
}
const sanitize = (s) => (s || '').replace(/[\\/:*?"<>|]+/g, '_').trim()

// --- parsea "15" o "15-20" (con o sin espacios) ---
function parseVigenciaInput(raw) {
  const s = (raw || '').trim()
  if (!s) return { dias: null, desde: null, hasta: null, ok: false }
  const m = s.match(/^(\d+)\s*-\s*(\d+)$/)
  if (m) {
    const a = parseInt(m[1], 10), b = parseInt(m[2], 10)
    if (Number.isFinite(a) && Number.isFinite(b)) {
      return { dias: null, desde: Math.min(a,b), hasta: Math.max(a,b), ok: true }
    }
    return { dias: null, desde: null, hasta: null, ok: false }
  }
  const n = parseInt(s, 10)
  if (Number.isFinite(n)) return { dias: n, desde: null, hasta: null, ok: true }
  return { dias: null, desde: null, hasta: null, ok: false }
}

/* ==========================
   NORMALIZACIÓN EN PROFUNDIDAD
   ========================== */
const isPlain = (x) => x && typeof x === 'object' && !Array.isArray(x)

// BFS hasta 4 niveles buscando la PRIMERA coincidencia por clave exacta
function deepPick(obj, keys, maxDepth = 4) {
  const want = new Set(keys)
  const q = [[obj, 0]]
  while (q.length) {
    const [node, d] = q.shift()
    if (!isPlain(node) || d > maxDepth) continue
    for (const [k, v] of Object.entries(node)) {
      if (want.has(k) && v != null && v !== '') return v
      if (isPlain(v)) q.push([v, d + 1])
    }
  }
  return undefined
}

// Igual que arriba pero por regex de clave
function deepPickRe(obj, re, maxDepth = 4) {
  const q = [[obj, 0]]
  while (q.length) {
    const [node, d] = q.shift()
    if (!isPlain(node) || d > maxDepth) continue
    for (const [k, v] of Object.entries(node)) {
      if (re.test(k) && v != null && v !== '') return v
      if (isPlain(v)) q.push([v, d + 1])
    }
  }
  return undefined
}

function normalizeRow(r) {
  if (!r || typeof r !== 'object') return {}

  const asValue = (v) => {
    if (v == null) return undefined
    const s = typeof v === 'string' ? v.trim() : v
    if (s === '' || s === '-') return undefined
    return s
  }

  const pick = (...keys) => asValue(deepPick(r, keys) ?? r[keys.find(k => r[k] != null)])

  // Responsable (Excel)
  const responsableExcel =
    asValue(
      deepPick(r, [
        'responsable_consolidado', 'responsableConsolidado',
        'responsable', 'responsable_excel', 'responsableExcel',
      ]) ?? deepPickRe(r, /responsab/i)
    )

  // Último cambio por
  const changedBy =
    asValue(
      deepPick(r, [
        'cambio_en_sistema_por', 'cambioEnSistemaPor',
        'ultimo_cambio_por', 'ultimoCambioPor',
        'last_changed_by', 'lastChangedBy',
        'changed_by', 'changedBy',
        'updated_by', 'updatedBy',
        'actor'
      ]) ?? deepPickRe(r, /(cambio|changed|modific|update|usuario|actor|ultimo)/i)
    )

  return {
    marchamo:              asValue(pick('marchamo')),
    tracking_code:         asValue(pick('tracking_code', 'trackingCode')),
    recipient_name:        asValue(pick('recipient_name', 'recipientName')),
    recipient_phone:       asValue(pick('recipient_phone', 'recipientPhone')),
    recipient_address:     asValue(pick('recipient_address', 'recipientAddress')),
    estado:                asValue(pick('estado')),
    devolucion_subtipo:    asValue(pick('devolucion_subtipo', 'devolucionSubtipo')),
    distrito_nombre:       asValue(pick('distrito_nombre', 'distritoNombre')),
    received_at:           asValue(pick('received_at', 'receivedAt')),
    delivered_at:          asValue(pick('delivered_at', 'deliveredAt')),
    returned_at:           asValue(pick('returned_at', 'returnedAt')),
    last_state_change_at:  asValue(pick('last_state_change_at', 'lastStateChangeAt')),
    merchandise_value:     asValue(pick('merchandise_value', 'merchandiseValue')),
    content_description:   asValue(pick('content_description', 'contentDescription')),
    observaciones:         asValue(pick('observaciones')),
    cambio_en_sistema_por: changedBy,
    responsable_consolidado: responsableExcel,
  }
}

function normalizeRows(rows) {
  if (!Array.isArray(rows)) return []
  return rows.map(normalizeRow)
}

export default function Inventario() {
  const [searchType, setSearchType] = useState('todos')
  const [query, setQuery] = useState('')
  const [rows, setRows] = useState([])
  const [columns, setColumns] = useState(FIXED_COLUMNS)
  const [loading, setLoading] = useState(false)

  // Paginación simple
  const [pageSize, setPageSize] = useState(20)
  const [offset, setOffset] = useState(0)

  // ✅ Estado para "Todos" (por defecto: recepción)
  const [estadoTodos, setEstadoTodos] = useState('ENTREGADO_A_TRANSPORTISTA_LOCAL')

  // Subfiltro devolución
  const [devSub, setDevSub] = useState('TODOS')

  // Total global
  const [totalCount, setTotalCount] = useState(0)

  useEffect(() => {
    if (searchType === 'todos') buscar(0, { searchType: 'todos', estadoTodos, devSub })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const fetchTotalCount = async (overrides = {}) => {
    try {
      const effSearchType = overrides.searchType ?? searchType
      const effEstado     = overrides.estadoTodos ?? estadoTodos
      const effDevSub     = overrides.devSub ?? devSub
      const q             = overrides.query ?? query

      let total = 0

      if (effSearchType === 'todos') {
        // Para NO_ENTREGABLE con subtipo usamos reportes/devolucion (para contar bien)
        if (effEstado === 'NO_ENTREGABLE') {
          const params = {}
          if (effDevSub !== 'TODOS') params.subtipo = effDevSub
          const { data: resp } = await api.get('/reportes/devolucion', { params })
          total = Array.isArray(resp) ? resp.length : 0
        } else {
          const params = { estado: effEstado }
          const { data } = await api.get('/busqueda/inventario/count', { params })
          total = data?.total ?? 0
        }
      } else if (effSearchType === 'marchamo') {
        if (!q.trim()) total = 0
        else {
          const { data } = await api.get(`/busqueda/marchamo/${encodeURIComponent(q)}/count`)
          total = data?.total ?? 0
        }
      } else if (effSearchType === 'tracking') {
        if (!q.trim()) total = 0
        else {
          const { data } = await api.get('/busqueda/tracking/count', { params: { q, like: 0 } })
          total = data?.total ?? 0
        }
      } else if (effSearchType === 'nombre') {
        if (!q.trim()) total = 0
        else {
          const { data } = await api.get('/busqueda/nombre/count', { params: { q, like: 1 } })
          total = data?.total ?? 0
        }
      } else if (effSearchType === 'direccion') {
        if (!q.trim()) total = 0
        else {
          const { data } = await api.get('/busqueda/direccion/count', { params: { q, like: 1 } })
          total = data?.total ?? 0
        }
      } else if (effSearchType === 'distrito') {
        if (!q.trim()) total = 0
        else {
          const { data } = await api.get(`/busqueda/distrito/${encodeURIComponent(q)}/count`)
          total = data?.total ?? 0
        }
      } else if (effSearchType === 'vigencia') {
        const parsed = parseVigenciaInput(q)
        if (!parsed.ok) total = 0
        else {
          const params = parsed.dias != null
            ? { dias: parsed.dias }
            : { desde: parsed.desde, hasta: parsed.hasta }
          const { data } = await api.get('/busqueda/vigencia/count', { params })
          total = data?.total ?? 0
        }
      }

      setTotalCount(total)
    } catch {
      setTotalCount(0)
    }
  }

  const buscar = async (customOffset, overrides = {}) => {
    try {
      setLoading(true)

      const effSearchType = overrides.searchType ?? searchType
      const effEstado     = overrides.estadoTodos ?? estadoTodos
      const effDevSub     = overrides.devSub ?? devSub
      const effQuery      = overrides.query ?? query
      const off = typeof customOffset === 'number' ? customOffset : (overrides.offset ?? offset)

      let data = []

      if (effSearchType === 'todos') {
        // NO_ENTREGABLE con subtipo: usamos /reportes/devolucion y paginamos en cliente
        if (effEstado === 'NO_ENTREGABLE') {
          const params = {}
          if (effDevSub !== 'TODOS') params.subtipo = effDevSub
          const { data: resp } = await api.get('/reportes/devolucion', { params })
          const normalized = normalizeRows(resp)
          data = normalized.slice(off, off + pageSize)
        } else {
          const params = { estado: effEstado, limit: pageSize, offset: off }
          const { data: resp } = await api.get('/busqueda/inventario', { params })
          data = normalizeRows(Array.isArray(resp) ? resp : [])
        }
      } else if (effSearchType === 'marchamo') {
        const { data: resp } = await api.get(`/busqueda/marchamo/${encodeURIComponent(effQuery || '')}`)
        data = normalizeRows(resp)
      } else if (effSearchType === 'tracking') {
        const { data: resp } = await api.get('/busqueda/tracking', { params: { q: effQuery, like: 0 } })
        data = normalizeRows(resp)
      } else if (effSearchType === 'nombre') {
        const { data: resp } = await api.get('/busqueda/nombre', { params: { q: effQuery, like: 1 } })
        data = normalizeRows(resp)
      } else if (effSearchType === 'direccion') {
        const { data: resp } = await api.get('/busqueda/direccion', { params: { q: effQuery, like: 1 } })
        data = normalizeRows(resp)
      } else if (effSearchType === 'distrito') {
        const { data: resp } = await api.get(`/busqueda/distrito/${encodeURIComponent(effQuery || '')}`)
        data = normalizeRows(resp)
      } else if (effSearchType === 'vigencia') {
        const parsed = parseVigenciaInput(effQuery)
        if (!parsed.ok) {
          data = []
        } else {
          const params = parsed.dias != null
            ? { dias: parsed.dias, limit: pageSize, offset: off }
            : { desde: parsed.desde, hasta: parsed.hasta, limit: pageSize, offset: off }
          const { data: resp } = await api.get('/busqueda/vigencia', { params })
          data = normalizeRows(resp)
        }
      }

      setRows(Array.isArray(data) ? data : [])
      setColumns(FIXED_COLUMNS)
      await fetchTotalCount({ searchType: effSearchType, estadoTodos: effEstado, devSub: effDevSub, query: effQuery })
    } catch (e) {
      toastErr(e)
    } finally {
      setLoading(false)
    }
  }

  const onChangePageSize = (v) => {
    const n = Number(v)
    if (!Number.isFinite(n) || n <= 0) return
    setPageSize(n)
    setOffset(0)
    buscar(0)
  }

  const cargarMas = () => {
    const next = offset + pageSize
    setOffset(next)
    buscar(next)
  }

  const handleSearchTypeClick = (key) => {
    const switching = key !== searchType
    setSearchType(key)
    setOffset(0)
    if (switching) setQuery('')
    if (key === 'todos') buscar(0, { searchType: 'todos', estadoTodos, devSub })
  }

  const handleEstadoClick = (estado) => {
    setEstadoTodos(estado)
    setDevSub('TODOS')
    setOffset(0)
    buscar(0, { searchType: 'todos', estadoTodos: estado, devSub: 'TODOS' })
  }

  const handleDevSubClick = (sub) => {
    setDevSub(sub)
    setOffset(0)
    buscar(0, { searchType: 'todos', estadoTodos: 'NO_ENTREGABLE', devSub: sub })
  }

  const onEnter = (e) => {
    if (e.key === 'Enter') {
      setOffset(0)
      buscar(0)
    }
  }

  /* Exportar por marchamo (usa filas mostradas) */
  const generarReporteMarchamo = () => {
    const marchamo = (query || '').trim()
    if (searchType !== 'marchamo') return
    if (!marchamo) { alert('Ingresá un marchamo primero'); return }
    if (!rows.length) { alert('No hay resultados para exportar'); return }

    const columns = FIXED_COLUMNS
    const headers = columns.map(c => prettyHeader(c))

    const data = rows.map(r => {
      const obj = {}
      columns.forEach(c => {
        let val = r[c]

        if (c === 'estado') {
          val = fmtEstado(r['estado'])
        }

        if (c === 'devolucion_subtipo') {
          val = fmtDevSub(r['estado'], r['devolucion_subtipo'])
        }

        if (['responsable_consolidado','cambio_en_sistema_por','observaciones'].includes(c)
            && (val == null || String(val).trim() === '')) val = '-'

        obj[prettyHeader(c)] = fmtCell(val)
      })
      return obj
    })

    const ws = XLSX.utils.json_to_sheet(data, { header: headers })
    autoWidth(ws, data, headers)
    const wb = XLSX.utils.book_new()
    XLSX.utils.book_append_sheet(wb, ws, 'Paquetes')

    const stamp = new Date().toISOString().slice(0,19).replace(/[:T]/g,'-')
    XLSX.writeFile(wb, `reporte_marchamo_${sanitize(marchamo)}_${stamp}.xlsx`, { compression: true })
  }

  // ¿Hay más páginas?
  const hasMore = (offset + pageSize) < totalCount

  return (
    <div className="page">
      <h2 style={{ marginBottom: 12 }}>Inventario</h2>

      {/* Filtros superiores */}
      <div style={{ display:'flex', gap:8, flexWrap:'wrap', alignItems:'center', marginBottom:10 }}>
        {SEARCH_TYPES.map(s => (
          <button
            key={s.key}
            className={`toggle ${searchType === s.key ? 'is-selected' : ''}`}
            aria-pressed={searchType === s.key}
            onClick={() => handleSearchTypeClick(s.key)}
          >
            {s.label}
          </button>
        ))}

        <div style={{ display:'flex', gap:8, alignItems:'center', marginLeft:8 }}>
          <label>Cantidad a mostrar:</label>
          <input
            type="number"
            min={1}
            value={pageSize}
            onChange={e => onChangePageSize(e.target.value)}
            style={{ width: 90, padding: 6, border: '1px solid #163E7A', borderRadius: 8 }}
            title="Cantidad de paquetes a listar"
          />
        </div>

        <button onClick={() => { setOffset(0); buscar(0) }}>
          {loading ? 'Buscando…' : 'Buscar'}
        </button>
      </div>

      {/* Controles específicos */}
      <div style={{ display:'flex', gap:8, flexWrap:'wrap', alignItems:'center', marginBottom:12 }}>
        {searchType === 'todos' && (
          <>
            <label>Estado a listar:</label>

            {ESTADOS_UI.map(opt => (
              <button
                key={opt.key}
                className={`toggle ${estadoTodos === opt.key ? 'is-selected' : ''}`}
                aria-pressed={estadoTodos === opt.key}
                onClick={() => handleEstadoClick(opt.key)}
              >
                {opt.label}
              </button>
            ))}

            {estadoTodos === 'NO_ENTREGABLE' && (
              <div style={{ display:'flex', gap:8, alignItems:'center', marginLeft:8 }}>
                <span style={{ opacity:.8 }}>Subfiltro:</span>
                {DEV_SUBS.map(s => (
                  <button
                    key={s.key}
                    className={`toggle ${devSub === s.key ? 'is-selected' : ''}`}
                    aria-pressed={devSub === s.key}
                    onClick={() => handleDevSubClick(s.key)}
                  >
                    {s.label}
                  </button>
                ))}
              </div>
            )}
          </>
        )}

        {searchType === 'marchamo' && (
          <>
            <label>Marchamo:</label>
            <input
              value={query}
              onChange={e => setQuery(e.target.value)}
              onKeyDown={onEnter}
              placeholder="HZCR-0000"
            />
            <button
              onClick={generarReporteMarchamo}
              disabled={loading || !query.trim() || rows.length === 0}
              title="Genera un Excel con los paquetes del marchamo buscado"
            >
              Generar reporte
            </button>
          </>
        )}

        {searchType === 'tracking' && (
          <>
            <label>Tracking:</label>
            <input
              value={query}
              onChange={e => setQuery(e.target.value)}
              onKeyDown={onEnter}
              placeholder="HZCR12345"
            />
          </>
        )}

        {searchType === 'nombre' && (
          <>
            <label>Nombre:</label>
            <input
              value={query}
              onChange={e => setQuery(e.target.value)}
              onKeyDown={onEnter}
              placeholder="Juan Perez"
            />
          </>
        )}

        {searchType === 'direccion' && (
          <>
            <label>Dirección:</label>
            <input
              value={query}
              onChange={e => setQuery(e.target.value)}
              onKeyDown={onEnter}
              placeholder="San José..."
            />
          </>
        )}

        {searchType === 'distrito' && (
          <>
            <label>Distrito:</label>
            <input
              value={query}
              onChange={e => setQuery(e.target.value)}
              onKeyDown={onEnter}
              placeholder="Roxana"
            />
          </>
        )}

        {searchType === 'vigencia' && (
          <>
            <label>Vigencia (días o rango):</label>
            <input
              value={query}
              onChange={e => setQuery(e.target.value)}
              onKeyDown={onEnter}
              placeholder="15  ó  15-20"
              title="Muestra paquetes con N días desde recepción, o un rango (p.ej. 15-20)."
            />
          </>
        )}
      </div>

      {/* Conteo TOTAL */}
      <div style={{ marginBottom: 8, fontWeight: 600, color: '#163E7A' }}>
        Total: {totalCount} paquetes
      </div>

      {/* Tabla */}
      <div
        style={{
          overflowX: 'auto',
          overflowY: 'auto',
          maxHeight: '65vh',
          border: '1px solid #163E7A',
          borderRadius: 8
        }}
      >
        <table style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead>
            <tr>
              {FIXED_COLUMNS.map(c => (
                <th key={c} style={{ textAlign:'left', padding: 8, borderBottom: '1px solid #163E7A' }}>
                  {HEADERS_ES[c] || c}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.length ? rows.map((r, idx) => (
              <tr key={idx} style={{ borderBottom: '1px solid rgba(0,0,0,0.05)' }}>
                {FIXED_COLUMNS.map(c => {
                  let rawVal = r[c]

                  if (c === 'estado') {
                    rawVal = fmtEstado(r['estado'])
                  }

                  if (c === 'devolucion_subtipo') {
                    rawVal = fmtDevSub(r['estado'], r['devolucion_subtipo'])
                  }

                  if (['responsable_consolidado','cambio_en_sistema_por','observaciones'].includes(c)
                      && (rawVal == null || String(rawVal).trim() === '')) rawVal = '-'

                  return (
                    <td
                      key={c}
                      style={{
                        padding: 8,
                        verticalAlign: 'top',
                        whiteSpace: c === 'observaciones' ? 'pre-wrap' : 'normal'
                      }}
                    >
                      {fmtCell(rawVal)}
                    </td>
                  )
                })}
              </tr>
            )) : (
              <tr>
                <td colSpan={FIXED_COLUMNS.length} style={{ padding: 16, textAlign: 'center', opacity: .7 }}>
                  Sin resultados
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {/* Paginación */}
      {['todos','vigencia'].includes(searchType) && (
        <div style={{ marginTop: 10, display:'flex', gap:8, alignItems:'center' }}>
          <span style={{ opacity:.75 }}>
            Mostrando {Math.min(totalCount, offset + rows.length)} / {totalCount}
          </span>
          <button onClick={cargarMas} disabled={loading || !hasMore}>
            {loading ? 'Cargando…' : 'Cargar más'}
          </button>
        </div>
      )}
    </div>
  )
}
