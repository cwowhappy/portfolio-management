import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import { fileURLToPath, URL } from "node:url";

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL(".", import.meta.url)),
    },
  },
  test: {
    environment: "jsdom",
    setupFiles: ["tests/setup.ts"],
    include: ["tests/**/*.test.{ts,tsx}"],
    coverage: {
      provider: "v8",
      // app/ 目录是 Next.js 框架接线层（页面壳 + 路由反代），由 smoke/e2e 覆盖，
      // 单测覆盖率聚焦业务代码：组件 + lib 工具层。
      include: ["components/**/*.{ts,tsx}", "lib/**/*.{ts,tsx}"],
      reporter: ["text", "html"],
      thresholds: {
        statements: 80,
        branches: 80,
      },
    },
  },
});
