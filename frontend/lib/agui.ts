// AG-UI 客户端：基于官方 @ag-ui/client（HttpAgent）与后端 /agui/run 通信。
// UI 层接收统一的宽松事件对象（AguiEvent）。

import { HttpAgent } from "@ag-ui/client";
import type { AguiEvent, RunAgentInput } from "./types";

export class AguiStreamError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "AguiStreamError";
  }
}

/**
 * 发起一轮 Agent 运行，逐事件回调（官方事件直接透传）。
 * 调用方传入 AbortSignal 可提前终止（对应 agent.abortRun()）。
 */
export async function runAgent(
  input: RunAgentInput,
  onEvent: (event: AguiEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  const agent = new HttpAgent({
    url: "/api/chat",
    threadId: input.threadId,
    // 前端持有完整历史（ADR-0004），一次种子进去
    initialMessages: input.messages as never[],
  });

  const subscription = agent.subscribe({
    onEvent: ({ event }) => {
      onEvent({ type: event.type, ...(event as Record<string, unknown>) } as AguiEvent);
    },
  });

  const abort = () => agent.abortRun();
  if (signal) {
    if (signal.aborted) {
      abort();
    } else {
      signal.addEventListener("abort", abort, { once: true });
    }
  }

  try {
    await agent.runAgent({ runId: input.runId });
  } catch (e) {
    if (signal?.aborted) return; // 用户主动停止，不视为错误
    const message =
      e instanceof Error && e.message && e.message !== "Failed to fetch"
        ? e.message
        : "无法连接 Agent 服务，请确认后端已启动";
    throw new AguiStreamError(message);
  } finally {
    if (signal) signal.removeEventListener("abort", abort);
    subscription.unsubscribe();
  }
}

/** 解析一个 SSE 块（保留：用于测试与低层调试）。 */
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
