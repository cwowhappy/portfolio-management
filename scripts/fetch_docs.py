#!/usr/bin/env python3
"""Fetch AgentScope Java v2 docs into docs/research/ for offline reference."""
import base64
import json
import os
import sys
import urllib.request

API = "https://api.github.com/repos/agentscope-ai/agentscope-java/contents/"
OUT = os.path.join(os.path.dirname(__file__), "..", "docs", "research")

FILES = [
    "docs/v2/en/docs/building-blocks/tool.md",
    "docs/v2/en/docs/building-blocks/model.md",
    "docs/v2/en/docs/building-blocks/agent.md",
    "docs/v2/en/docs/building-blocks/context.md",
    "docs/v2/en/docs/building-blocks/middleware.md",
    "docs/v2/en/docs/building-blocks/message-and-event.md",
    "docs/v2/en/docs/building-blocks/permission-system.md",
    "docs/v2/en/docs/harness/architecture.md",
    "docs/v2/en/docs/others/going-to-production.md",
    "docs/v2/en/docs/others/faq.md",
    "docs/v2/en/docs/others/release-notes.md",
    "docs/v2/en/integration/model/deepseek.md",
    "docs/v2/en/integration/overview.md",
    "docs/v2/en/integration/session/overview.md",
    "docs/v2/en/integration/session/index.md",
    "docs/v2/en/integration/ecosystem/chat-completions-web.md",
    "docs/v2/en/integration/protocol/agui.md",
]

os.makedirs(OUT, exist_ok=True)
for path in FILES:
    try:
        with urllib.request.urlopen(API + path, timeout=30) as resp:
            data = json.load(resp)
        content = base64.b64decode(data["content"]).decode("utf-8")
        name = path.split("/")[-1]
        dest = os.path.join(OUT, name)
        with open(dest, "w", encoding="utf-8") as f:
            f.write(content)
        print(f"OK  {path} -> {len(content)} bytes")
    except Exception as e:
        print(f"ERR {path}: {e}", file=sys.stderr)
