// src/api.js
import axios from 'axios'

const BASE = (import.meta.env.VITE_API_URL || '/api').replace(/\/$/, '')

export const api = axios.create({
  baseURL: BASE,
  timeout: 160000,
  withCredentials: true,
})

// ---------- Toast helpers (mínimos, no intrusivos) ----------
export function toastOk(msg) {
  try {
    if (typeof window !== 'undefined' && window?.alert) {
      window.alert(msg || 'OK')
    } else {
      console.log('OK:', msg)
    }
  } catch {
    console.log('OK:', msg)
  }
}

export function toastErr(err) {
  const msg =
    err?.response?.data?.message ||
    err?.response?.data?.error ||
    err?.message ||
    'Error'
  try {
    if (typeof window !== 'undefined' && window?.alert) {
      window.alert(msg)
    } else {
      console.error('Error:', msg)
    }
  } catch {
    console.error('Error:', msg)
  }
}

// ============================
//  APIs de dominio
// ============================

// Saco
export const sacoApi = {
  exists: (marchamo) =>
    api.get(`/sacos/${encodeURIComponent(marchamo)}/exists`),
  // { marchamo, defaultDistritoNombre? }
  create: (payload) => api.post('/sacos', payload),
  deleteIfEmpty: (marchamo) =>
    api.delete(`/sacos/${encodeURIComponent(marchamo)}`),
  // Compat opcional (proyecto viejo)
  deleteIfEmptyCompat: (marchamo) =>
    api.post('/sacos/eliminarSacoSiVacio', { marchamo }),
}

// Paquete (recepción / eliminación / cambios de estado)
export const paqueteApi = {
  // { trackingCode|tracking, marchamo, distritoNombre, receivedAt? }
  create: (payload) => api.post('/paquetes', payload),
  exists: (tracking) => api.get(`/paquetes/${encodeURIComponent(tracking)}/exists`),
  delete: (tracking) => api.delete(`/paquetes/${encodeURIComponent(tracking)}`),

  // Cambios de estado (Transportistas)
  // Preferido: /estado/bulk y /estado/tracking
  estadoBulk: (payload) => api.post('/estado/bulk', payload),
  estadoBulkCompat: (payload) => api.post('/paquetes/estado/bulk', payload),
  estadoTracking: (payload) => api.post('/estado/tracking', payload),

  // Eliminación en lote (ADMIN)
  bulkDelete: (payload) => api.post('/paquetes/bulk-delete', payload),
}

export const authApi = {
  me: () => api.get('/auth/me'),
  login: (payload) => api.post('/auth/login', payload),
  logout: () => api.post('/auth/logout'),
}
