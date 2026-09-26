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
    // @copilotkit/react-core v2 index 入口带 index.css 副作用：默认 node_modules 走外部化，
    // css import 会落到 Node ESM 加载器直接报「Unknown file extension ".css"」。inline 后经
    // vite 转换链（测试环境 css 恒 stub），未 mock 该模块的测试文件（RuntimeProvider/Sidebar 等）
    // 得以真实加载。注意 index 与 v2/headless 是两套 chunk/context 实例——provider 与 hooks
    // 必须同用 index 入口（threadId 绑定依赖同一 context，见 ADR-0012）。
    server: {
      deps: {
        inline: ["@copilotkit/react-core"],
      },
    },
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
