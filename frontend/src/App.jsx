// src/App.jsx
import { useCallback, useEffect, useRef, useState } from 'react'
import { NavLink, Routes, Route, useLocation, useNavigate } from 'react-router-dom'
import Recepcion from './pages/Recepcion.jsx'
import Inventario from './pages/Inventario.jsx'
import Entregas from './pages/Entregas.jsx'
import Reportes from './pages/Reportes.jsx'
import Dashboard from './pages/Dashboard.jsx'
import Importar from './pages/Importar.jsx'
import Login from './pages/Login.jsx'
import Register from './pages/Register.jsx'
import AdminProfile from './pages/AdminProfile.jsx'
import HistorialCambios from './pages/HistorialCambios.jsx'
import logoUrl from './assets/cargo_logo.svg'
import HojaRuta from './pages/HojaRuta.jsx'
import { api } from './api'

// ----- Helper de clase activa para NavLink -----
const link = ({ isActive }) => 'nav__link' + (isActive ? ' is-active' : '')

// ----- Guard sencillo: verifica sesión con /api/auth/me -----
function RequireAuth({ children }) {
  const [checking, setChecking] = useState(true)
  const [ok, setOk] = useState(false)
  const loc = useLocation()
  const navigate = useNavigate()

  useEffect(() => {
    let alive = true
    ;(async () => {
      try {
        await api.get('/auth/me')
        if (!alive) return
        setOk(true)
      } catch {
        if (!alive) return
        const next = encodeURIComponent(loc.pathname + loc.search)
        navigate(`/login?next=${next}`, { replace: true })
      } finally {
        if (alive) setChecking(false)
      }
    })()
    return () => {
      alive = false
    }
  }, [loc.pathname, loc.search, navigate])

  if (checking) {
    return (
      <div style={{ padding: 24, textAlign: 'center', color: '#163E7A' }}>
        Verificando sesión…
      </div>
    )
  }
  return ok ? children : null
}

// ----- Guard para ADMIN -----
function RequireAdmin({ children }) {
  const [checking, setChecking] = useState(true)
  const [ok, setOk] = useState(false)
  const loc = useLocation()
  const navigate = useNavigate()

  useEffect(() => {
    let alive = true
    ;(async () => {
      try {
        const { data } = await api.get('/auth/me') // { id, username, name, role }
        if (!alive) return
        if (data?.role === 'ADMIN') setOk(true)
        else navigate('/', { replace: true })
      } catch {
        if (!alive) return
        const next = encodeURIComponent(loc.pathname + loc.search)
        navigate(`/login?next=${next}`, { replace: true })
      } finally {
        if (alive) setChecking(false)
      }
    })()
    return () => {
      alive = false
    }
  }, [loc.pathname, loc.search, navigate])

  if (checking) {
    return (
      <div style={{ padding: 24, textAlign: 'center', color: '#163E7A' }}>
        Verificando permisos…
      </div>
    )
  }
  return ok ? children : null
}

// ----- Botón Logout -----
function LogoutButton({ className = '', style = {} }) {
  const navigate = useNavigate()
  const loc = useLocation()

  const onLogout = async () => {
    try {
      await api.post('/auth/logout')
    } catch {}
    const next = encodeURIComponent(loc.pathname + loc.search)
    navigate(`/login?next=${next}`, { replace: true })
  }

  return (
    <button onClick={onLogout} title="Cerrar sesión" className={className} style={style}>
      Cerrar sesión
    </button>
  )
}

export default function App() {
  const loc = useLocation()
  const isAuthPage = loc.pathname === '/login' || loc.pathname === '/register'

  // ---- Avisos (campanita) ----
  const [avisosSummary, setAvisosSummary] = useState({
    intento1: 0,
    intento2: 0,
    noEntregable: 0,
    total: 0,
    hasAny: false,
  })

  // 👇 En vez de polling cada 60s, solo refrescamos si pasó 1 día (o si es "force")
  const ONE_DAY_MS = 24 * 60 * 60 * 1000
  const LAST_FETCH_KEY = 'avisos_last_fetch_ms'
  const lastFetchRef = useRef(0)

  const loadAvisosSummary = useCallback(async ({ force = false } = {}) => {
    try {
      const now = Date.now()

      // inicializa desde localStorage (por si el usuario recarga)
      if (!lastFetchRef.current) {
        lastFetchRef.current = Number(localStorage.getItem(LAST_FETCH_KEY) || 0)
      }

      // si no es forzado y no ha pasado 1 día, no consultamos
      if (!force && now - lastFetchRef.current < ONE_DAY_MS) return

      const { data } = await api.get('/busqueda/avisos/summary', { timeout: 15000 })
      const intento1 = Number(data?.intento1 ?? 0) || 0
      const intento2 = Number(data?.intento2 ?? 0) || 0
      const noEntregable = Number(data?.noEntregable ?? 0) || 0
      const total = Number(data?.total ?? intento1 + intento2 + noEntregable) || 0
      const hasAny = Boolean(data?.hasAny ?? total > 0)

      setAvisosSummary({ intento1, intento2, noEntregable, total, hasAny })

      lastFetchRef.current = now
      localStorage.setItem(LAST_FETCH_KEY, String(now))
    } catch {
      // Si falla, no “pisamos” lastFetch; así puede reintentar al volver a focus/visible
      setAvisosSummary({
        intento1: 0,
        intento2: 0,
        noEntregable: 0,
        total: 0,
        hasAny: false,
      })
    }
  }, [])

  // Cargar al entrar a páginas privadas (login -> app), forzado
  useEffect(() => {
    if (isAuthPage) return
    loadAvisosSummary({ force: true })
  }, [isAuthPage, loadAvisosSummary])

  // Refrescar como máximo 1 vez cada 24h cuando vuelve a estar visible / focus,
  // y refresco inmediato cuando se disparan acciones internas (avisos:refresh)
  useEffect(() => {
    if (isAuthPage) return

    const onVisibleOrFocus = () => {
      if (document.visibilityState === 'visible') {
        loadAvisosSummary({ force: false })
      }
    }

    const onRefresh = () => loadAvisosSummary({ force: true })

    document.addEventListener('visibilitychange', onVisibleOrFocus)
    window.addEventListener('focus', onVisibleOrFocus)
    window.addEventListener('avisos:refresh', onRefresh)

    return () => {
      document.removeEventListener('visibilitychange', onVisibleOrFocus)
      window.removeEventListener('focus', onVisibleOrFocus)
      window.removeEventListener('avisos:refresh', onRefresh)
    }
  }, [isAuthPage, loadAvisosSummary])

  // Trae "me" y lo ACTUALIZA también cuando cambia la ruta
  const [me, setMe] = useState(null)
  useEffect(() => {
    let alive = true
    ;(async () => {
      try {
        const { data } = await api.get('/auth/me')
        if (alive) setMe(data) // { id, username, name, role }
      } catch {
        if (alive) setMe(null)
      }
    })()
    return () => {
      alive = false
    }
  }, [loc.pathname])

  const displayUser = me?.name || me?.username || '—'
  const displaySucursal = 'Transportista'

  return (
    <>
      {/* Topbar (se oculta en login/register) */}
      {!isAuthPage && (
        <header className="topbar">
          <div className="topbar__inner" style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
            <div className="brand" style={{ display: 'flex', alignItems: 'center' }}>
              <img src={logoUrl} alt="Cargo FSR" height={32} />
              <strong style={{ marginLeft: 8, color: '#163E7A' }}>Inventario</strong>
            </div>

            <nav className="nav" style={{ display: 'flex', gap: 12 }}>
              <NavLink className={link} to="/dashboard">
                Dashboard
              </NavLink>
              <NavLink className={link} to="/">
                Recepción
              </NavLink>
              <NavLink className={link} to="/inventario">
                Consulta
              </NavLink>
              <NavLink className={link} to="/entregas">
                Cambio de Status
              </NavLink>
              <NavLink className={link} to="/reportes">
                Reportes
              </NavLink>
              <NavLink className={link} to="/hoja-ruta">
                Hoja de Ruta
              </NavLink>
              <NavLink className={link} to="/importar">
                Importar
              </NavLink>
              <NavLink className={link} to="/historial">
                Historial
              </NavLink>

              {/* Solo ADMIN */}
              {me?.role === 'ADMIN' && (
                <NavLink className={link} to="/admin">
                  Perfil de administrador
                </NavLink>
              )}
            </nav>

            {/* ACCIONES A LA DERECHA */}
            <div
              className="topbar__actions"
              style={{
                marginLeft: 'auto',
                display: 'flex',
                alignItems: 'center',
                gap: 10,
              }}
            >
              {/* Campanita de avisos */}
              <button
                type="button"
                className={`notifBell${avisosSummary.hasAny ? ' notifBell--active' : ''}`}
                aria-label="Avisos"
                title={
                  avisosSummary.hasAny
                    ? `Avisos pendientes: ${avisosSummary.total} (1er intento: ${avisosSummary.intento1}, 2do intento: ${avisosSummary.intento2}, no entregable: ${avisosSummary.noEntregable})`
                    : 'No hay avisos pendientes'
                }
              >
                <svg viewBox="0 0 24 24" aria-hidden="true">
                  <path
                    d="M12 22a2.5 2.5 0 0 0 2.45-2h-4.9A2.5 2.5 0 0 0 12 22Zm7-6V11a7 7 0 1 0-14 0v5l-2 2v1h18v-1l-2-2Z"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="2"
                    strokeLinejoin="round"
                  />
                </svg>
                {avisosSummary.hasAny && <span className="notifBell__badge">{avisosSummary.total}</span>}
              </button>

              {/* Label usuario en sesión */}
              <div
                title="Usuario en sesión"
                style={{
                  background: '#ffffff',
                  color: '#163E7A',
                  border: '2px solid #163E7A',
                  borderRadius: 10,
                  padding: '8px 12px',
                  fontWeight: 700,
                  lineHeight: 1,
                  whiteSpace: 'nowrap',
                }}
              >
                {displayUser}
              </div>

              <div
                title="Sucursal"
                style={{
                  background: '#ffffff',
                  color: '#163E7A',
                  border: '2px solid #163E7A',
                  borderRadius: 10,
                  padding: '8px 12px',
                  fontWeight: 700,
                  lineHeight: 1,
                  whiteSpace: 'nowrap',
                }}
              >
                {displaySucursal}
              </div>

              {/* Botón cerrar sesión (a la derecha) */}
              <LogoutButton
                style={{
                  background: '#ffffff',
                  color: '#163E7A',
                  border: '2px solid #163E7A',
                  borderRadius: 10,
                  padding: '8px 12px',
                  fontWeight: 700,
                  cursor: 'pointer',
                  whiteSpace: 'nowrap',
                }}
              />
            </div>
          </div>
        </header>
      )}

      <main className="page">
        <Routes>
          {/* PÚBLICAS */}
          <Route path="/login" element={<Login />} />
          <Route path="/register" element={<Register />} />

          {/* PRIVADAS */}
          <Route
            path="/dashboard"
            element={
              <RequireAuth>
                <Dashboard />
              </RequireAuth>
            }
          />
          <Route
            path="/"
            element={
              <RequireAuth>
                <Recepcion />
              </RequireAuth>
            }
          />
          <Route
            path="/inventario"
            element={
              <RequireAuth>
                <Inventario />
              </RequireAuth>
            }
          />
          <Route
            path="/entregas"
            element={
              <RequireAuth>
                <Entregas />
              </RequireAuth>
            }
          />
          <Route
            path="/reportes"
            element={
              <RequireAuth>
                <Reportes />
              </RequireAuth>
            }
          />
          <Route
            path="/hoja-ruta"
            element={
              <RequireAuth>
                <HojaRuta />
              </RequireAuth>
            }
          />
          <Route
            path="/importar"
            element={
              <RequireAuth>
                <Importar />
              </RequireAuth>
            }
          />
          <Route
            path="/historial"
            element={
              <RequireAuth>
                <HistorialCambios />
              </RequireAuth>
            }
          />

          {/* ADMIN ONLY */}
          <Route
            path="/admin"
            element={
              <RequireAdmin>
                <AdminProfile />
              </RequireAdmin>
            }
          />
        </Routes>
      </main>
    </>
  )
}