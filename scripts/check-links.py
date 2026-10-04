#!/usr/bin/env python3
"""Markdown 链接门禁：全仓 .md 相对链接断链即失败（make check-links / CI link-gate job）。

- 扫描范围：README.md、AGENTS.md、docs/、features/ 全部 .md
- 豁免：docs/technology/research/agentscope/（vendored 官方文档摘录，指向上游未拷贝章节的
  链接预期不可达，见该目录 README）；代码围栏与行内代码内的示例链接（如 `![](url)`）不扫描
- 起源：2026-09-12 文档体系更新计划附录的一次性脚本；2026-10-04 固化进 CI——此前
  5 个特性目录 26 处断链（相对深度少一级）落盘时无人拦。
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
EXEMPT_PREFIX = os.path.join("docs", "technology", "research", "agentscope") + os.sep
LINK = re.compile(r"\[[^\]]*\]\(([^)\s]+)\)")
REMOTE = ("http://", "https://", "mailto:")


def strip_inline_code(text: str) -> str:
    return re.sub(r"`[^`\n]*`", "", text)


def iter_targets():
    yield "README.md"
    yield "AGENTS.md"
    for base in ("docs", "features"):
        for dirpath, _, files in os.walk(os.path.join(ROOT, base)):
            for name in sorted(files):
                if name.endswith(".md"):
                    rel = os.path.relpath(os.path.join(dirpath, name), ROOT)
                    if not rel.startswith(EXEMPT_PREFIX):
                        yield rel


def main() -> int:
    bad = []
    count = 0
    for rel in iter_targets():
        count += 1
        path = os.path.join(ROOT, rel)
        out_lines, in_fence = [], False
        for line in open(path, encoding="utf-8"):
            if line.lstrip().startswith("```"):
                in_fence = not in_fence
                continue
            if not in_fence:
                out_lines.append(line)
        text = strip_inline_code("".join(out_lines))
        for m in LINK.finditer(text):
            link = m.group(1).split("#")[0]
            if not link or link.startswith(REMOTE):
                continue
            target = os.path.normpath(os.path.join(os.path.dirname(path), link))
            if not os.path.exists(target):
                bad.append(f"{rel} -> {link}")
    if bad:
        print("\n".join(bad))
        print(f"\nFAIL: {len(bad)} 处断链（扫描 {count} 个 markdown 文件）")
        return 1
    print(f"OK: 零断链（扫描 {count} 个 markdown 文件，豁免 agentscope vendored 与代码内示例链接）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
