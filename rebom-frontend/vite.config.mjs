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
        port: 3005,
        proxy: {
            '^/graphql': {
                target: 'http://localhost:4000',
                ws: true,
                changeOrigin: true
            },
            '^/restapi': {
                target: 'http://localhost:4000',
                ws: true,
                changeOrigin: true
            }
        }
    }
})