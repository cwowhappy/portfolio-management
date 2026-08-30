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
      // 覆盖率聚焦业务代码：组件 + lib 工具层 + API 反代路由（有路由级单测）。
      // 页面壳（app/**/page.tsx、layout.tsx、error.tsx 等框架接线层）仍由 smoke/e2e 覆盖，不纳入。
      include: ["components/**/*.{ts,tsx}", "lib/**/*.{ts,tsx}", "app/api/**/*.{ts,tsx}"],
      reporter: ["text", "html"],
      thresholds: {
        statements: 80,
        branches: 80,
        // portfolio 域曾是覆盖率洼地（分支 67%），单独卡门槛防退化。
        "components/portfolio/**": { branches: 80 },
        "lib/portfolioApi.ts": { branches: 80 },
      },
    },
  },
});
