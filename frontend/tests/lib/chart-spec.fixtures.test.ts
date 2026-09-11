import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { ChartSpecSchema } from "@/lib/chart-spec";

// 双端契约真源：features/chat-rich-content/fixtures/*.json（backend ChartSpecFixtureTest 逐字符守护）。
// tests/lib → ../../../ = 仓库根。
// ⚠ 不用 new URL("<字面量>", import.meta.url)：vite 会把它当静态资源引用改写，jsdom 下不再是 file:// 。
const fixturesDir = join(
  dirname(fileURLToPath(import.meta.url)),
  "..",
  "..",
  "..",
  "features",
  "chat-rich-content",
  "fixtures",
);
const files = ["kline.json", "valuation.json", "overview.json", "financials.json"] as const;

describe("ChartSpec 双端 fixtures（前端侧）", () => {
  it.each(files)("zod 解析通过：%s", (f) => {
    const raw = readFileSync(join(fixturesDir, f), "utf8");
    expect(ChartSpecSchema.safeParse(JSON.parse(raw)).success).toBe(true);
  });
});
