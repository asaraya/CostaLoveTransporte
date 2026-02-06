// src/api.js
import axios from 'axios';

const BASE = (import.meta.env.VITE_API_URL || '/api').replace(/\/$/, '');

export const api = axios.create({
  baseURL: BASE,
  timeout: 160000,
  withCredentials: true,
});

// ---------- Toast helpers (mínimos, no intrusivos) ----------
export function toastOk(msg) {
  // Mantén simple y consistente con toastErr; si más adelante tienes un UI de toasts, puedes reemplazar aquí
  try {
    if (typeof window !== 'undefined' && window?.alert) {
      window.alert(msg || 'OK');
    } else {
      console.log('OK:', msg);
    }
  } catch {
    console.log('OK:', msg);
  }
}

export function toastErr(err) {
  const msg =
    err?.response?.data?.message ||
    err?.response?.data?.error ||
    err?.message ||
    'Error';
  try {
    if (typeof window !== 'undefined' && window?.alert) {
      window.alert(msg);
    } else {
      console.error('Error:', msg);
    }
  } catch {
    console.error('Error:', msg);
  }
}

// ============================
//  APIs de dominio
// ============================

// Saco
export const sacoApi = {
  exists: (marchamo) =>
    api.get(`/sacos/${encodeURIComponent(marchamo)}/exists`),
  create: (payload) =>
    api.post('/sacos', payload), // { marchamo, defaultUbicacionCodigo? }
  deleteIfEmpty: (marchamo) =>
    api.delete(`/sacos/${encodeURIComponent(marchamo)}`),
  // Compat opcional si el backend también expone este alias:
  deleteIfEmptyCompat: (marchamo) =>
    api.post('/sacos/eliminarSacoSiVacio', { marchamo }),
};

// Paquete
export const paqueteApi = {
  create: (payload) =>
    api.post('/paquetes', payload), // { trackingCode|tracking, marchamo, ubicacionCodigo, receivedAt? }
  exists: (tracking) =>
    api.get(`/paquetes/${encodeURIComponent(tracking)}/exists`),
  delete: (tracking) =>
    api.delete(`/paquetes/${encodeURIComponent(tracking)}`),
  estadoBulk: (payload) =>
    api.post('/paquetes/estado/bulk', payload),
};

// Ubicaciones
export const ubicacionApi = {
  // Ajusta si tu backend usa otro path (ej. /api/ubicaciones/activas)
  activas: () => api.get('/ubicaciones/activas'),
};

// (Opcional) Si luego vuelves a usar reportes desde el front:
// export const reportesApi = {
//   diario: (fechaISO) => api.get('/reportes/diario', { params: { fecha: fechaISO } }),
//   entregados: (params) => api.get('/reportes/entregados', { params }),
//   devolucion: (params) => api.get('/reportes/devolucion', { params }),
// };

export const authApi = {
  me: () => api.get('/auth/me'),
  login: (payload) => api.post('/auth/login', payload),
  logout: () => api.post('/auth/logout'),
};
