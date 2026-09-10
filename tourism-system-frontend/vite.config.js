import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'path'

// Vite配置
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src')
    }
  },
  server: {
    port: 5173,
    // 开发阶段代理后端接口
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      '/api/ai': {
        target: 'http://localhost:8002',
        changeOrigin: true
      }
    }
  },
  build: {
    // 输出到SpringBoot静态资源目录
    outDir: '../tourism-system-backend/src/main/resources/static',
    emptyOutDir: true
  }
})
