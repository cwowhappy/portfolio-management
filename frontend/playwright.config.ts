import { defineConfig, devices } from "@playwright/test";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

// 载入根 .env（若存在）供测试读取 DEEPSEEK_API_KEY 等，用于门控 AI 对话用例。
try {
  const env = readFileSync(resolve(process.cwd(), "../.env"), "utf8");
  for (const line of env.split("\n")) {
    const m = line.match(/^\s*([A-Za-z_][\w.]*)\s*=\s*(.*)\s*$/);
    if (m && !(m[1] in process.env)) {
      process.env[m[1]] = m[2].replace(/^["']|["']$/g, "");
    }
  }
} catch {
  // 无 .env 时忽略
}

/**
 * 浏览器端到端测试：真实浏览器 → 前端(3000) → 后端(8080) → 真实行情/LLM。
 * webServer 会自动拉起后端与前端（已运行时则复用）。运行：pnpm test:e2e
 */
export default defineConfig({
  testDir: "./e2e",
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  // 行情走真实公开接口，偶发抖动；本地也重试 1 次以吸收瞬时波动
  retries: process.env.CI ? 2 : 1,
  reporter: process.env.CI ? "github" : "list",
  use: {
    baseURL: "http://localhost:3000",
    trace: "on-first-retry",
    screenshot: "only-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: [
    {
      command: "bash ../scripts/e2e-backend.sh",
      url: "http://localhost:8080/api/agent/health",
      timeout: 180_000,
      reuseExistingServer: !process.env.CI,
    },
    {
      command: "bash ../scripts/e2e-frontend.sh",
      url: "http://localhost:3000",
      timeout: 120_000,
      reuseExistingServer: !process.env.CI,
    },
  ],
});
