// vite.config.ts - EXAMPLE for the React app (not part of the backend build).
//
// Why a proxy: the API's login cookie is SameSite=Strict and its CSRF token must be READ by JavaScript from the
// XSRF-TOKEN cookie. Both only work when the browser sees ONE origin. The dev server serves React AND forwards /api/*
// to the backend, so the browser never makes a cross-origin call. The backend sends no CORS headers on purpose.
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
          // Cookies are host-only (no Domain attribute), so the browser stores them for the dev server's origin;
          // no cookieDomainRewrite is needed.
        },
      },
    },
  };
});
