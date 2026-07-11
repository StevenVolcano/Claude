import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      // During development the PocketBase backend runs on :8090.
      '/api': 'http://127.0.0.1:8090',
      '/_': 'http://127.0.0.1:8090',
    },
  },
})
