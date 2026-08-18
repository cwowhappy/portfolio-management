// AG-UI SSE 客户端：POST /api/chat（Next 反代 → 后端 /agui/run），逐事件解析。

import type { AguiEvent, RunAgentInput } from "./types";

export class AguiStreamError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "AguiStreamError";
  }
}

/**
 * 发起一轮 Agent 运行，逐事件回调。
 * 调用方传入 AbortSignal 可提前终止。
 */
export async function runAgent(
  input: RunAgentInput,
  onEvent: (event: AguiEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  const res = await fetch("/api/chat", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(input),
    signal,
  });

  if (!res.ok) {
    let message = "服务暂时不可用";
    try {
      const body = await res.json();
      if (body?.message) message = body.message;
    } catch {
      // ignore
    }
    throw new AguiStreamError(message);
  }

  if (!res.body) {
    throw new AguiStreamError("响应流为空");
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });

    let sep: number;
    while ((sep = buffer.indexOf("\n\n")) >= 0) {
      const block = buffer.slice(0, sep);
      buffer = buffer.slice(sep + 2);
      const event = parseSseBlock(block);
      if (event) onEvent(event);
    }
  }
}

/** 解析一个 SSE 块（可含 event: 与 data: 行）。 */
export function parseSseBlock(block: string): AguiEvent | null {
  let type = "";
  const dataLines: string[] = [];
  for (const line of block.split("\n")) {
    if (line.startsWith("event:")) {
      type = line.slice(6).trim();
    } else if (line.startsWith("data:")) {
      dataLines.push(line.slice(5).trimStart());
    }
  }
  if (!type || dataLines.length === 0) return null;
  try {
    const parsed = JSON.parse(dataLines.join("\n"));
    return { type, ...parsed } as AguiEvent;
  } catch {
    return null;
  }
}
