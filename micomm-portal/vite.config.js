import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// Forwards API calls to the services on this machine, so the portal also works when opened
// from another computer (http://<this machine's IP>:5173). The Origin header is dropped so the
// services see a same-origin request and their localhost-only CORS settings don't apply.
const dropOrigin = (proxy) => proxy.on('proxyReq', (proxyReq) => proxyReq.removeHeader('origin'))

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    host: true,
    port: 5173,
    proxy: {
      '/tm-api': {
        target: 'http://localhost:8081',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/tm-api/, ''),
        configure: dropOrigin,
      },
      '/api': {
        target: 'http://localhost:8082',
        changeOrigin: true,
        configure: dropOrigin,
      },
    },
  },
})
