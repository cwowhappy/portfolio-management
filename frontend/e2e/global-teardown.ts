import { execFileSync } from "node:child_process";
import { resolve } from "node:path";

export default function globalTeardown() {
  // GitHub Actions 用临时 postgres 容器（跑完即销毁）且 runner 无 psql；本地（含 make test-e2e 的 CI=true）持久化 DB 需清理。
  if (process.env.GITHUB_ACTIONS) return;
  const script = resolve(process.cwd(), "../scripts/e2e-cleanup.sh");
  try {
    execFileSync("bash", [script], { stdio: "inherit" });
  } catch {
    console.warn("[e2e] 清理 e2e_% 用户失败（psql 不可用或 DB 未启动），忽略");
  }
}
