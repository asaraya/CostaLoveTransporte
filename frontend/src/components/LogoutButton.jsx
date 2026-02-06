import { useLocation, useNavigate } from 'react-router-dom'
import { authApi } from '../api'

export default function LogoutButton() {
  const navigate = useNavigate()
  const loc = useLocation()

  const onLogout = async () => {
    try {
      await authApi.logout()
    } catch (_) {
      // si ya no hay sesión, seguimos igual
    } finally {
      // vuelve al login y conserva el "next" para regresar luego si inicia de nuevo
      const next = encodeURIComponent(loc.pathname + loc.search)
      navigate(`/login?next=${next}`, { replace: true })
    }
  }

  return (
    <button onClick={onLogout} title="Cerrar sesión">
      Cerrar sesión
    </button>
  )
}
