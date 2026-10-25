/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // If 5173 is taken, fail instead of moving to another port.
    strictPort: true,
    proxy: {
      // Spec 008 (D2): the browser sees the API on its own origin, so the API needs no CORS.
      // 127.0.0.1, not localhost: on Windows localhost may go to ::1.
      '/api': 'http://127.0.0.1:8080',
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test-setup.ts'],
  },
})
