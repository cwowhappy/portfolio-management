import { test, expect } from "@playwright/test";
import { existsSync } from "node:fs";
import { resolve } from "node:path";
import { registerAndApprove, TEST_PASSWORD, uniqueUsername } from "./helpers";

// issue #26 回归钉：AG-UI threadId 绑定会话表 id。
// 后端 harness 会话态按 (userId, threadId) 键控落盘 backend/.agentscope/state/<userId>/<threadId>；
// 修复前 CopilotKit 每次页面加载自铸新 UUID，state 目录键 ≠ 会话表 id（刷新即脱钩、多轮记忆截断）。
// 此处页内取「当前会话 id + 当前用户 id」，一次真实运行后在 Node 侧断言对应 state 目录存在。
const hasAdminSeed = !!(process.env.ADMIN_USERNAME && process.env.ADMIN_PASSWORD);

test.describe("threadId 绑定（issue #26）", () => {
  test.skip(!process.env.DEEPSEEK_API_KEY, "未配置 DEEPSEEK_API_KEY，跳过真实对话");
  test.skip(!hasAdminSeed, "未配置 ADMIN_USERNAME/ADMIN_PASSWORD（无种子管理员），跳过");

  test("一次真实运行后，服务端会话态目录 = (userId, 会话表 id)", async ({ page }) => {
    await registerAndApprove(page, uniqueUsername("threadid"), TEST_PASSWORD);
    const input = page.getByPlaceholder(/问行情、看走势、读财报/);
    await input.fill("用一句话介绍你自己");
    const sendBtn = page.getByRole("button", { name: "发送" });
    await expect(sendBtn).toBeEnabled({ timeout: 30_000 });
    await sendBtn.click();
    await expect(page.getByRole("button", { name: "■ 停止" })).toBeHidden({ timeout: 180_000 });

    const me = (await (await page.request.get("/api/auth/me")).json()) as { id: number };
    const convs = (await (await page.request.get("/api/conversations")).json()) as Array<{ id: string }>;
    expect(convs.length).toBeGreaterThan(0);
    const convId = convs[0].id;

    // 运行结束到 state 落盘可能有短暂延迟，轮询最多 5s
    const stateDir = resolve(__dirname, "../../backend/.agentscope/state", String(me.id), convId);
    let found = false;
    for (let i = 0; i < 25 && !found; i++) {
      found = existsSync(stateDir);
      if (!found) await new Promise((r) => setTimeout(r, 200));
    }
    expect(found, `state 目录应按 (userId, 会话id) 落盘：${stateDir}`).toBeTruthy();
  });
});
