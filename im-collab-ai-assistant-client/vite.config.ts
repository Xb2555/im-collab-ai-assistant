// vite.config.ts
import path from "path"
import react from "@vitejs/plugin-react"
import { defineConfig } from "vite"

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
  server: {
    port: 5173,
    proxy: {
      // 拦截所有 /api 开头的请求，转发到后端
      "/api": {
        target: "http://81.71.143.236:18080",
        changeOrigin: true,
      },
    },
  },
})