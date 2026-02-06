// src/pages/Login.jsx
import { useState } from 'react'
import { useLocation, useNavigate, Link } from 'react-router-dom'
import { api, toastErr } from '../api'
import logoUrl from '../assets/cargo_logo.svg'
import '../styles/auth.css'

export default function Login() {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [showPw, setShowPw] = useState(false) // <-- NUEVO
  const [loading, setLoading] = useState(false)
  const [err, setErr] = useState('')

  const location = useLocation()
  const navigate = useNavigate()

  const onSubmit = async (e) => {
    e.preventDefault()
    setErr('')
    try {
      setLoading(true)
      await api.post('/auth/login', { username, password })
      const params = new URLSearchParams(location.search)
      const nxt = params.get('next') || '/inventario'
      navigate(nxt, { replace: true })
    } catch (error) {
      setErr(error?.response?.data?.message || 'Credenciales inválidas')
      toastErr(error)
    } finally {
      setLoading(false)
    }
  }

  const nxt = new URLSearchParams(location.search).get('next') || '/inventario'

  return (
    <div className="auth-wrap">
      <div className="auth-card">
        <div className="auth-head">
          <div className="brand">
            <img src={logoUrl} alt="Cargo FSR" />
            <strong>Inventario</strong>
          </div>
          <h1>Iniciar sesión</h1>
        </div>

        <form className="auth-form" onSubmit={onSubmit}>
          <div className="auth-field">
            <label className="auth-label" htmlFor="username">Usuario</label>
            <input
              id="username"
              className="auth-input"
              type="text"
              placeholder="usuario"
              value={username}
              onChange={e => setUsername(e.target.value)}
              autoComplete="username"
              required
            />
          </div>

          <div className="auth-field">
            <label className="auth-label" htmlFor="password">Contraseña</label>

            {/* Contenedor para input + botón mostrar/ocultar */}
            <div style={{ display:'flex', gap:8, alignItems:'center' }}>
              <input
                id="password"
                className="auth-input"
                type={showPw ? 'text' : 'password'}   // <-- NUEVO
                placeholder="••••••••"
                value={password}
                onChange={e => setPassword(e.target.value)}
                autoComplete="current-password"
                required
              />
              <button
                type="button"
                onClick={() => setShowPw(v => !v)}
                aria-pressed={showPw}
                aria-label={showPw ? 'Ocultar contraseña' : 'Mostrar contraseña'}
                title={showPw ? 'Ocultar contraseña' : 'Mostrar contraseña'}
                style={{
                  padding:'8px 12px',
                  border:'1px solid #163E7A',
                  borderRadius:8,
                  background:'#fff',
                  color:'#163E7A',
                  fontWeight:600,
                  cursor:'pointer'
                }}
              >
                {showPw ? 'Ocultar' : 'Mostrar'}
              </button>
            </div>

            {err ? <div className="form-error">{err}</div> : null}
          </div>

          <button className="auth-btn" type="submit" disabled={loading}>
            {loading ? 'Ingresando…' : 'Ingresar'}
          </button>
        </form>
      </div>
    </div>
  )
}
