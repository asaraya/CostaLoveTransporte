// src/pages/Register.jsx
import { useState } from 'react'
import { useLocation, useNavigate, Link } from 'react-router-dom'
import { api, toastErr, toastOk } from '../api'
import logoUrl from '../assets/cargo_logo.svg'
import '../styles/auth.css'

export default function Register() {
  const [fullName, setFullName] = useState('')
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [loading, setLoading] = useState(false)
  const [err, setErr] = useState('')

  const location = useLocation()
  const navigate = useNavigate()
  const params = new URLSearchParams(location.search)
  const nxt = params.get('next') || '/inventario'

  const onSubmit = async (e) => {
    e.preventDefault()
    setErr('')
    try {
      setLoading(true)
      await api.post('/auth/register', { fullName, username, password })
      toastOk('Usuario creado. Ahora puedes iniciar sesión.')
      navigate(`/login?next=${encodeURIComponent(nxt)}`, { replace: true })
    } catch (error) {
      setErr(error?.response?.data?.message || 'No se pudo crear el usuario')
      toastErr(error)
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="auth-wrap">
      <div className="auth-card">
        <div className="auth-head">
          <div className="brand">
            <img src={logoUrl} alt="Cargo FSR" />
            <strong>Inventario</strong>
          </div>
          <h1>Crear cuenta</h1>
        </div>

        <form className="auth-form" onSubmit={onSubmit}>
          <div className="auth-field">
            <label className="auth-label" htmlFor="fullName">Nombre completo</label>
            <input
              id="fullName"
              className="auth-input"
              type="text"
              placeholder="Nombre y apellidos"
              value={fullName}
              onChange={e => setFullName(e.target.value)}
              required
            />
          </div>

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
            <input
              id="password"
              className="auth-input"
              type="password"
              placeholder="Mínimo 6 caracteres"
              value={password}
              onChange={e => setPassword(e.target.value)}
              minLength={6}
              autoComplete="new-password"
              required
            />
            {err ? <div className="form-error">{err}</div> : null}
            <small className="small">Se guardará de forma segura en el servidor.</small>
          </div>

          <button className="auth-btn" type="submit" disabled={loading}>
            {loading ? 'Creando…' : 'Registrarme'}
          </button>
        </form>

        <div className="auth-foot">
          ¿Ya tienes cuenta?{' '}
          <Link className="auth-link" to={`/login?next=${encodeURIComponent(nxt)}`}>
            Iniciar sesión
          </Link>
        </div>
      </div>
    </div>
  )
}
