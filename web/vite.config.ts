import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  base: './',
  plugins: [react()],
  server: { host: '0.0.0.0' },
  build: {
    rollupOptions: {
      input: {
        main: 'index.html',
        analytics: 'analytics.html',
      },
    },
  },
})
