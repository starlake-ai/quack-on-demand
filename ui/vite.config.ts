import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';

export default defineConfig({
  plugins: [react()],
  base: '/ui/',
  build: {
    outDir: path.resolve(__dirname, '../src/main/resources/ui'),
    emptyOutDir: true
  },
  server: {
    proxy: {
      '/api': 'http://localhost:20900'
    }
  }
});