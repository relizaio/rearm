import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from "url";

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [vue()],
  esbuild: {
    supported: {
      'top-level-await': true
    },
  },

  resolve: {
    alias: {
        "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
    server: {
        port: 3000,
        proxy: {
            '^/v1': {
                target: 'http://localhost:8086',
                ws: true,
                changeOrigin: true
            },
            '^/login': {
                target: 'http://localhost:8086',
                ws: true
            },
            '^/api': {
                target: 'http://localhost:8086',
                ws: true,
                changeOrigin: true
            },
            '^/graphql': {
                target: 'http://localhost:8086',
                ws: true,
                changeOrigin: true
            },
            '^/kauth': {
                target: 'http://localhost:9080',
                ws: true,
                changeOrigin: true
            }
        }
    }
})