#!/usr/bin/env node
// chart 载荷体积断言：esbuild minify bundle + gzip（与 05 §5.2 的 218KB 实测同方法）。
// 上限 240KB = 实测 218KB + ~10% 余量；超限 = 树摇回归（全量入口 379KB）。
import { build } from "esbuild";
import { gzipSync } from "node:zlib";

const LIMIT_BYTES = 245_760; // 240 * 1024

const result = await build({
  entryPoints: ["scripts/chart-bundle-entry.ts"],
  bundle: true,
  minify: true,
  write: false,
  format: "esm",
  target: "es2020",
  logLevel: "silent",
});
const js = result.outputFiles[0].text;
const gzipBytes = gzipSync(Buffer.from(js), { level: 9 }).length;
const kb = (gzipBytes / 1024).toFixed(1);
if (gzipBytes > LIMIT_BYTES) {
  console.error(`chart bundle gzip ${kb}KB > 上限 240KB（按需实测基线 218KB）——疑似全量入口/树摇回归，检查 no-restricted-imports 守卫`);
  process.exit(1);
}
console.log(`chart bundle gzip ${kb}KB ≤ 240KB ✓`);
