// src/App.jsx
import { useEffect, useState } from 'react'
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
import logoUrl from './assets/cargo_logo.svg'
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
    return () => { alive = false }
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
    return () => { alive = false }
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
    try { await api.post('/auth/logout') } catch {}
    const next = encodeURIComponent(loc.pathname + loc.search)
    navigate(`/login?next=${next}`, { replace: true })
  }

  return (
    <button
      onClick={onLogout}
      title="Cerrar sesión"
      className={className}
      style={style}
    >
      Cerrar sesión
    </button>
  )
}

export default function App() {
  const loc = useLocation()
  const isAuthPage = loc.pathname === '/login' || loc.pathname === '/register'

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
    return () => { alive = false }
  }, [loc.pathname])

  const displayUser = me?.name || me?.username || '—'
  const displaySucursal = "Transportista"
  return (
    <>
      {/* Topbar (se oculta en login/register) */}
      {!isAuthPage && (
        <header className="topbar">
          <div className="topbar__inner" style={{ display:'flex', alignItems:'center', gap:16 }}>
            <div className="brand" style={{ display:'flex', alignItems:'center' }}>
              <img src={logoUrl} alt="Cargo FSR" height={32} />
              <strong style={{ marginLeft: 8, color:'#163E7A' }}>Inventario</strong>
            </div>

            <nav className="nav" style={{ display:'flex', gap:12 }}>
              <NavLink className={link} to="/dashboard">Dashboard</NavLink>
              <NavLink className={link} to="/">Recepción</NavLink>
              <NavLink className={link} to="/inventario">Consulta</NavLink>
              <NavLink className={link} to="/entregas">Cambio de Status</NavLink>
              <NavLink className={link} to="/reportes">Reportes</NavLink>
              <NavLink className={link} to="/importar">Importar</NavLink>

              {/* Solo ADMIN */}
              {me?.role === 'ADMIN' && (
                <NavLink className={link} to="/admin">Perfil de administrador</NavLink>
              )}
            </nav>

            {/* ACCIONES A LA DERECHA */}
            <div
              className="topbar__actions"
              style={{
                marginLeft: 'auto',
                display: 'flex',
                alignItems: 'center',
                gap: 10
              }}
            >
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
                  whiteSpace: 'nowrap'
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
                  whiteSpace: 'nowrap'
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
                  whiteSpace: 'nowrap'
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
            path="/importar"
            element={
              <RequireAuth>
                <Importar />
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
