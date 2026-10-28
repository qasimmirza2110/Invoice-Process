// vite.config.ts - EXAMPLE for the React app (not part of the backend build).
//
// Why a proxy: the backend sends no CORS headers unless its owner allows an exact origin. The dev server serves React
// AND forwards /api/* to the backend, so the browser only talks to one origin and no CORS is involved. The
// Authorization: Bearer header added by the API client is forwarded unchanged.
//
// API_PROXY_TARGET has no VITE_ prefix on purpose: it is read here, in Node, and never ends up in the browser bundle.
import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const target = env.API_PROXY_TARGET; // e.g. https://YOUR-ACTUAL-SERVICE.onrender.com (from the handoff package)
  if (!target) {
    throw new Error('Set API_PROXY_TARGET in .env.local (see env.example)');
  }
  return {
    plugins: [react()],
    server: {
      proxy: {
        '/api': {
          target,
          changeOrigin: true, // the backend sees its own host name
          secure: true,       // verify the backend's TLS certificate
        },
      },
    },
  };
});
