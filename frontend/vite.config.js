import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react-swc'

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': { target: 'http://localhost:8080',
        changeOrigin: true,
        proxyTimeout: 120000, // tiempo de inactividad del backend
        timeout: 120000
      }
    }
  }
})
