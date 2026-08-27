# 前端 AG-UI 组件迁移：assistant-ui → CopilotKit 实施计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 把一期对话前端的 AG-UI 渲染层从 assistant-ui（@assistant-ui/react 0.15 + @assistant-ui/react-ag-ui）整体切换到 CopilotKit v2（@copilotkit/react-core + @copilotkit/runtime 1.68.1），后端 AgentScope `/agui/run` 零改动，完整保留"研报终端"设计体系与会话/历史/工具卡/思考折叠/反馈等既有行为。

**Architecture:** CopilotKit 采用"运行时（Next.js 路由）+ headless 前端"接线：新增 `/api/copilotkit` 路由，用 `CopilotRuntime + HttpAgent(@ag-ui/client)` 把请求转发到后端 `POST /agui/run`（AG-UI SSE 透传）；前端用 `CopilotKit` Provider + `useAgent` 读流式消息，自绘消息列表/输入框/工具卡，完全不动后端协议与会话模型（ADR-0004 前端持有历史继续用 localStorage）。

**Tech Stack:** Next.js 15 + React 19 · CopilotKit 1.68.1（@copilotkit/react-core/v2、@copilotkit/runtime/v2）· @ag-ui/client 0.0.57 · react-markdown + remark-gfm + highlight.js · AgentScope Java（后端不动）

---

# 一、迁移方案总览（调整方案）

## 1.1 现状 → 目标映射

| 关注点 | 现状（assistant-ui） | 目标（CopilotKit） | 改动方式 |
|---|---|---|---|
| 协议客户端 | @assistant-ui/react-ag-ui + @ag-ui/client HttpAgent（RuntimeProvider.tsx 内直连 /api/chat） | CopilotRuntime + @ag-ui/client HttpAgent（服务端路由转发） | 新建 app/api/copilotkit/[[...slug]]/route.ts |
| 反代 | app/api/chat/route.ts（浏览器→后端 SSE 透传） | 由 CopilotKit 运行时路由承担（服务端→后端） | 删除 api/chat |
| Provider | AssistantRuntimeProvider + useAgUiRuntime | CopilotKit runtimeUrl="/api/copilotkit" + useAgent | 新建 app/providers.tsx |
| 消息流 | ThreadPrimitive.Messages + MessagePrimitive.Parts | useAgent().agent.messages 自绘 | 重写 ThreadArea.tsx |
| 输入区 | ComposerPrimitive + ThreadPrimitive.If | 自绘 textarea + 发送/停止按钮（agent.addMessage + copilotkit.runAgent / agent.abortRun） | 重写 ThreadArea.tsx |
| 会话列表 | ThreadListPrimitive（对接 localStorage adapter） | 自绘 <ul> 直读 lib/sessions.ts（useThreads 仅 Intelligence 模式可用，本地 SSE 不可用） | 重写 Sidebar.tsx |
| 工具卡 | MessagePrimitive.Parts tools.Fallback + 自研 ToolCallCard | 直读 message.toolCalls[] 渲染 ToolCallCard（AG-UI 消息原生携带 toolCalls） | 适配 ToolCallCard.tsx |
| 思考折叠 | ReasoningMessagePartProps.text | message.reasoning（AG-UI REASONING 事件聚合到 assistant 消息） | 重写 ThreadArea.tsx |
| Markdown/代码高亮 | @assistant-ui/react-markdown（MarkdownTextPrimitive + CodeHeader/SyntaxHighlighter） | react-markdown（已是依赖）+ 自研 CodeBlock/InlineCode | 重写 CodeHighlight.tsx |
| 反馈 👍/👎 | ActionBarPrimitive.Feedback* + feedback adapter | 自绘按钮，沿用 invest.feedback.<id> localStorage 键 | 重写 ThreadArea.tsx |
| 历史持久化 | runtime.thread.subscribe → saveMessages | useAgent + useEffect 订阅 agent.messages → saveMessages | 重写 RuntimeProvider.tsx |

## 1.2 关键决策

1. **采用"运行时路由"而非"前端直连"**：CopilotKit v2 官方推荐 CopilotRuntime 服务端路由（runtimeUrl="/api/copilotkit"）。后端 /agui/run 是标准 AG-UI 端点，用 @ag-ui/client 的 HttpAgent 直接对接（与 CrewAI/ADK/LlamaIndex 等集成同一模式），后端零改动。
2. **headless 自绘，不用 CopilotChat 预制组件**：项目有 bespoke"研报终端"视觉与中文工具卡，CopilotChat/CopilotPopup 样式与交互对不上。用 useAgent + useCopilotKit 自绘，仅复用 CopilotKit 的协议/状态机。
3. **会话模型不变（ADR-0004）**：CopilotKit 的 useThreads 仅在 Intelligence（托管）模式可用，本地 SSE 模式下不可用。会话列表继续由 lib/sessions.ts（localStorage）持有，线程切换 = 切换 useAgent({ threadId }) 并 agent.setMessages(history) 回灌历史。
4. **工具卡直读 message.toolCalls**：不走 useRenderTool/useRenderToolCall 注册表，直接映射 AG-UI 消息自带的 toolCalls[]（{ id, name, arguments, result, status }），减少抽象、最大复用现有 ToolCallCard。
5. **清理由 assistant-ui 引入的死代码**：lib/agui.ts（runAgent 已无调用方，仅 parseSseBlock 被测试引用）与 api/chat 反代一并删除。

## 1.3 风险与对策

- **CopilotKit v1 多次 breaking，v2（1.68.1）是新 API 面**：所有 import 走 @copilotkit/react-core/v2、@copilotkit/runtime/v2 子路径，严禁混用包根（v1）导入。锁 1.68.1。
- **message.reasoning / message.toolCalls 字段形态需实测确认**：Task 7 设"形态验证"步，用真实后端冒烟确认字段名与取值；实现采用宽松类型 + 防御性读取。
- **历史回灌可能引发一次多余持久化**：agent.setMessages 触发 messages 变化 → 持久化同内容（幂等，可接受）；持久化跳过空消息。
- **@copilotkit/react-core peer 依赖 zod**：显式加入 zod 依赖避免 pnpm 警告与运行时缺失。

---

# 二、实施任务

> 约定：所有命令在 frontend/ 下执行（pnpm test = vitest run，pnpm build = next build）。每步独立可验证，验证通过后提交。

### Task 1: 依赖切换

**Files:**
- Modify: frontend/package.json

**Step 1: 改依赖** — 删除三个 @assistant-ui/*，新增 CopilotKit 相关：

```jsonc
// dependencies 内：
// 删除：
"@assistant-ui/react": "^0.15.14",
"@assistant-ui/react-ag-ui": "^0.0.54",
"@assistant-ui/react-markdown": "^0.14.10",
// 新增：
"@copilotkit/react-core": "^1.68.1",
"@copilotkit/runtime": "^1.68.1",
"zod": "^3.25.0",
// 保留（runtime 路由继续 import HttpAgent）：
"@ag-ui/client": "^0.0.57",
```

**Step 2: 安装并锁版本**

```bash
cd frontend && pnpm install
```

预期：pnpm-lock.yaml 更新，无 peer 警告（zod 已补）；@assistant-ui/* 从 lock 移除。

**Step 3: 确认 import 仍能编译（此时旧代码还在）**

```bash
cd frontend && pnpm build
```

预期：@assistant-ui/* 包已删除，旧代码会报"找不到模块"，属预期（后续任务逐个替换）。此步只确认 CopilotKit 包安装成功：ls node_modules/@copilotkit 应含 react-core、runtime。

**Step 4: Commit**

```bash
git add frontend/package.json frontend/pnpm-lock.yaml
git commit -m "chore(frontend): swap assistant-ui deps for CopilotKit"
```

### Task 2: 新建 CopilotKit 运行时路由

**Files:**
- Create: frontend/app/api/copilotkit/[[...slug]]/route.ts

**Step 1: 写路由**

```ts
// CopilotKit 运行时：浏览器 → /api/copilotkit → 后端 POST /agui/run（AG-UI SSE）。
import {
  CopilotRuntime,
  createCopilotRuntimeHandler,
} from "@copilotkit/runtime/v2";
import { HttpAgent } from "@ag-ui/client";

export const dynamic = "force-dynamic";

const BACKEND = process.env.BACKEND_URL ?? "http://localhost:8080";

const runtime = new CopilotRuntime({
  agents: {
    // agent id 与后端 agentscope.agui.default-agent-id 一致
    invest: new HttpAgent({ url: `${BACKEND}/agui/run` }),
  },
});

const handler = createCopilotRuntimeHandler({
  runtime,
  basePath: "/api/copilotkit",
});

export const GET = handler;
export const POST = handler;
export const OPTIONS = handler;
```

**Step 2: 起服务验证路由（后端需先启动）**

```bash
# 终端 A：后端
cd backend && ./gradlew bootRun --console=plain
# 终端 B：前端
cd frontend && pnpm dev
# 终端 C：探测 agent 元信息
curl -s http://localhost:3000/api/copilotkit/info
```

预期：返回 200 JSON，含 invest agent（多路由模式暴露 GET /info）。

**Step 3: Commit**

```bash
git add frontend/app/api/copilotkit/[[...slug]]/route.ts
git commit -m "feat(frontend): add CopilotKit runtime route (AG-UI HttpAgent)"
```

### Task 3: 挂载 CopilotKit Provider

**Files:**
- Create: frontend/app/providers.tsx
- Modify: frontend/app/layout.tsx

**Step 1: 写 client provider**

```tsx
"use client";

import { CopilotKit } from "@copilotkit/react-core/v2";
import "@copilotkit/react-core/v2/styles.css";
import type { ReactNode } from "react";

export function Providers({ children }: { children: ReactNode }) {
  return (
    <CopilotKit
      runtimeUrl="/api/copilotkit"
      onError={({ code, error }) => {
        console.error("[copilotkit]", code, error);
      }}
    >
      {children}
    </CopilotKit>
  );
}
```

**Step 2: 在 layout 包裹 children**

layout.tsx 顶部加 import { Providers } from "./providers";，并把 <body> 内顶层用 <Providers> 包一层（header/main 保留原样，作为 children）：

```tsx
<body>
  <Providers>
    <header className="fixed ...">…原 header…</header>
    <main className="h-dvh pt-12">{children}</main>
  </Providers>
</body>
```

**Step 3: 验证编译**

```bash
cd frontend && pnpm build
```

预期：构建通过（此时旧 assistant-ui 组件仍在，若报错先记录，Task 4-6 会替换；若构建卡在旧依赖报错，可先注释 ChatPage 的 RuntimeProvider 引用暂不提交，但推荐按任务顺序先完成 Task 4 再统一验证）。

**Step 4: Commit**

```bash
git add frontend/app/providers.tsx frontend/app/layout.tsx
git commit -m "feat(frontend): mount CopilotKit provider at root layout"
```

### Task 4: 重写会话层 RuntimeProvider（含历史回灌与持久化）

**Files:**
- Modify: frontend/components/chat/RuntimeProvider.tsx（整体重写）
- Modify: frontend/lib/types.ts（新增 AG-UI 消息宽松类型，删废弃 AguiEvent/RunAgentInput）

**Step 1: 在 lib/types.ts 增类型、删废弃类型**

删除 AguiRequestMessage、RunAgentInput、AguiEvent；新增：

```ts
/** CopilotKit / AG-UI 会话消息（宽松类型，防御性读取） */
export interface AgentMessage {
  id: string;
  role: string;
  content?: unknown;
  reasoning?: string;
  toolCalls?: ToolCallMessage[];
  [key: string]: unknown;
}

export interface ToolCallMessage {
  id: string;
  name: string;
  arguments?: string;
  result?: unknown;
  status?: "inProgress" | "executing" | "complete";
}
```

（ChatMessage 与行情类型保留不动。）

**Step 2: 写新 RuntimeProvider**

```tsx
"use client";

import { useAgent, UseAgentUpdate } from "@copilotkit/react-core/v2";
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import {
  deleteSession,
  listSessions,
  loadMessages,
  newThreadId,
  saveMessages,
  type SessionMeta,
} from "@/lib/sessions";
import type { AgentMessage, ChatMessage } from "@/lib/types";

export const AGENT_ID = "invest";

// ———— 会话上下文（Sidebar / ThreadArea 读取） ————

interface ChatRuntimeContextValue {
  sessions: SessionMeta[];
  currentThreadId: string;
  newThread: () => void;
  switchThread: (threadId: string) => void;
  deleteThread: (threadId: string) => void;
}

const ChatRuntimeContext = createContext<ChatRuntimeContextValue | null>(null);

export function useChatRuntime(): ChatRuntimeContextValue {
  const ctx = useContext(ChatRuntimeContext);
  if (!ctx) throw new Error("useChatRuntime 必须在 RuntimeProvider 内使用");
  return ctx;
}

// ———— 历史格式转换 ————

function historyToAgentMessages(msgs: ChatMessage[]): AgentMessage[] {
  return msgs.map((m) => ({ id: m.id, role: m.role, content: m.content }));
}

function agentMessagesToHistory(messages: AgentMessage[]): ChatMessage[] {
  const out: ChatMessage[] = [];
  for (const m of messages) {
    if (m.role !== "user" && m.role !== "assistant") continue;
    const content = typeof m.content === "string" ? m.content.trim() : "";
    if (!content) continue;
    out.push({ id: m.id, role: m.role, content, createdAt: Date.now() });
  }
  return out;
}

// ———— 历史回灌 + 持久化（须在 CopilotKit Provider 内） ————

function AgentSync({
  threadId,
  onPersist,
}: {
  threadId: string;
  onPersist: (threadId: string, msgs: ChatMessage[]) => void;
}) {
  const { agent } = useAgent({
    agentId: AGENT_ID,
    threadId,
    updates: [UseAgentUpdate.OnMessagesChanged],
  });

  // 切换/首次加载：本地历史回灌 agent（后端 server-side-memory=false，需完整历史）
  useEffect(() => {
    const history = loadMessages(threadId);
    if (history.length > 0) {
      agent.setMessages(historyToAgentMessages(history));
    }
  }, [agent, threadId]);

  // 消息变化 → localStorage 持久化 + 刷新会话列表
  useEffect(() => {
    const saved = agentMessagesToHistory(agent.messages ?? []);
    if (saved.length > 0) onPersist(threadId, saved);
  }, [agent.messages, threadId, onPersist]);

  return null;
}

// ———— Provider ————

export function RuntimeProvider({ children }: { children: ReactNode }) {
  const [sessions, setSessions] = useState<SessionMeta[]>(() => listSessions());
  const [currentThreadId, setCurrentThreadId] = useState<string>(() => {
    const list = listSessions();
    return list.length > 0 ? list[0].id : newThreadId();
  });

  const refresh = useCallback(() => setSessions(listSessions()), []);

  const newThread = useCallback(() => setCurrentThreadId(newThreadId()), []);
  const switchThread = useCallback((id: string) => setCurrentThreadId(id), []);
  const deleteThread = useCallback(
    (id: string) => {
      deleteSession(id);
      refresh();
      if (id === currentThreadId) setCurrentThreadId(newThreadId());
    },
    [currentThreadId, refresh],
  );

  const onPersist = useCallback(
    (threadId: string, msgs: ChatMessage[]) => {
      saveMessages(threadId, msgs);
      refresh();
    },
    [refresh],
  );

  const value = useMemo(
    () => ({ sessions, currentThreadId, newThread, switchThread, deleteThread }),
    [sessions, currentThreadId, newThread, switchThread, deleteThread],
  );

  return (
    <ChatRuntimeContext.Provider value={value}>
      <AgentSync threadId={currentThreadId} onPersist={onPersist} />
      {children}
    </ChatRuntimeContext.Provider>
  );
}
```

**Step 3: 验证（暂以编译为准，完整冒烟在 Task 10）**

```bash
cd frontend && npx tsc --noEmit
```

预期：RuntimeProvider.tsx 无类型错误（此时 ThreadArea/Sidebar 仍引用旧 assistant-ui 组件会报错，记录并继续）。

**Step 4: Commit**

```bash
git add frontend/components/chat/RuntimeProvider.tsx frontend/lib/types.ts
git commit -m "refactor(frontend): rewrite runtime provider on CopilotKit useAgent"
```

### Task 5: 重写代码高亮（react-markdown 组件）

**Files:**
- Modify: frontend/components/chat/CodeHighlight.tsx（整体重写）

**Step 1: 重写（去掉 @assistant-ui/react-markdown 类型，改 react-markdown 组件）**

```tsx
"use client";

import { useState, type ReactNode } from "react";
import hljs from "highlight.js/lib/common";

function highlight(code: string, language?: string): string {
  try {
    const lang = language && hljs.getLanguage(language) ? language : "plaintext";
    return hljs.highlight(code, { language: lang }).value;
  } catch {
    return code.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  }
}

/** 代码块（fenced code）：语言标签 + 复制 + highlight.js 高亮。 */
export function CodeBlock({
  className,
  children,
}: {
  className?: string;
  children?: ReactNode;
}) {
  const [copied, setCopied] = useState(false);
  const language = /language-([\w-]+)/.exec(className ?? "")?.[1] ?? "text";
  const code = String(children ?? "").replace(/\n$/, "");
  const copy = () => {
    void navigator.clipboard?.writeText(code).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    });
  };
  return (
    <div className="my-2 overflow-hidden rounded-md">
      <div className="flex items-center justify-between rounded-t-md border border-b-0 border-[color:var(--color-line-soft)] bg-[color:var(--color-panel-2)] px-3 py-1.5">
        <span className="font-[family-name:var(--font-mono)] text-[11px] uppercase tracking-wider text-[color:var(--color-ink-faint)]">
          {language}
        </span>
        <button
          type="button"
          onClick={copy}
          className="rounded px-2 py-0.5 text-[11px] text-[color:var(--color-ink-dim)] transition-colors hover:text-[color:var(--color-up)]"
        >
          {copied ? "已复制" : "复制"}
        </button>
      </div>
      <pre className="overflow-x-auto rounded-b-md bg-[color:var(--color-bg-soft)]">
        <code
          className="hljs block p-3 text-[12px] leading-relaxed"
          dangerouslySetInnerHTML={{ __html: highlight(code, language) }}
        />
      </pre>
    </div>
  );
}

/** 行内代码。 */
export function InlineCode({ children }: { children?: ReactNode }) {
  return (
    <code className="rounded bg-[color:var(--color-bg-soft)] px-1.5 py-0.5 font-[family-name:var(--font-mono)] text-[12px] text-[color:var(--color-up)]">
      {children}
    </code>
  );
}
```

**Step 2: 验证编译（后续 ThreadArea 会引用这两个导出）**

```bash
cd frontend && npx tsc --noEmit
```

预期：本文件无类型错误。

**Step 3: Commit**

```bash
git add frontend/components/chat/CodeHighlight.tsx
git commit -m "refactor(frontend): rewrite code highlight for react-markdown"
```

### Task 6: 适配工具卡 ToolCallCard 到 AG-UI 状态机

**Files:**
- Modify: frontend/components/chat/ToolCallCard.tsx
- Modify: frontend/tests/ToolCallCard.test.tsx

**Step 1: 改 ToolCallCard 的 status 为 AG-UI camelCase 并支持 result 直读**

```tsx
"use client";

import { useState } from "react";

const TOOL_LABELS: Record<string, string> = {
  search_stock: "搜索股票",
  get_quote: "实时行情",
  get_kline: "K线走势",
  get_financials: "财务指标",
  get_news: "个股新闻",
  get_market_overview: "大盘速览",
};

function labelOf(name: string) {
  return TOOL_LABELS[name] ?? name;
}

function prettyArgs(argsText: string): string {
  if (!argsText) return "";
  try {
    const obj = JSON.parse(argsText);
    const parts: string[] = [];
    if (obj.code) parts.push(String(obj.code));
    if (obj.query) parts.push(String(obj.query));
    if (obj.period) parts.push(String(obj.period));
    if (obj.limit) parts.push("近" + String(obj.limit) + "根");
    return parts.join(" · ");
  } catch {
    return argsText.slice(0, 60);
  }
}

export interface ToolCallCardProps {
  toolCallId: string;
  toolName: string;
  argsText: string;
  result?: unknown;
  isError?: boolean;
  /** AG-UI ToolCall.status（inProgress/executing/complete） */
  status?: "inProgress" | "executing" | "complete";
}

/** 工具进度卡片：running 脉冲动画，展开折叠展示参数与结果。 */
export default function ToolCallCard({
  toolName,
  argsText,
  result,
  isError,
  status,
}: ToolCallCardProps) {
  const [open, setOpen] = useState(false);
  const running = status === "inProgress" || status === "executing";
  const resultText =
    typeof result === "string" ? result : result != null ? JSON.stringify(result) : null;
  const failed =
    isError || (status === "complete" && resultText != null && /error/i.test(resultText));
  const summary = prettyArgs(argsText);

  return (
    <div
      className={
        "tool-card my-2 w-full max-w-[560px] overflow-hidden " +
        (running ? "running" : "")
      }
    >
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className="flex w-full items-center gap-2.5 px-3.5 py-2.5 text-left"
      >
        <span className="grid h-5 w-5 shrink-0 place-items-center rounded border border-[color:var(--color-line)] text-[10px] text-[color:var(--color-up)]">
          {running ? <span className="tool-pulse" /> : failed ? "!" : "✓"}
        </span>
        <span className="text-[13px] text-[color:var(--color-ink)]">{labelOf(toolName)}</span>
        {summary && (
          <span className="tabular truncate text-[12px] text-[color:var(--color-ink-faint)]">
            {summary}
          </span>
        )}
        <span
          className={
            "ml-auto text-[11px] transition-transform duration-200 " +
            (open
              ? "rotate-180 text-[color:var(--color-ink-dim)]"
              : "text-[color:var(--color-ink-faint)]")
          }
        >
          ▾
        </span>
      </button>
      {open && (
        <div className="border-t border-[color:var(--color-line-soft)] px-3.5 py-2.5">
          <pre className="max-h-56 overflow-auto whitespace-pre-wrap break-all font-[family-name:var(--font-mono)] text-[11px] leading-relaxed text-[color:var(--color-ink-dim)]">
            {resultText
              ? resultText.length > 2000
                ? resultText.slice(0, 2000) + "…"
                : resultText
              : running
                ? argsText || "执行中…"
                : "无结果"}
          </pre>
        </div>
      )}
    </div>
  );
}
```

**Step 2: 改测试的 status 形状**

frontend/tests/ToolCallCard.test.tsx：把 status={{ type: "running" }} → status="inProgress"，status={{ type: "complete" }} → status="complete"，status={{ type: "incomplete", reason: "error" }} → status="complete" result='{"error":"..."}'（触发失败态）。

```bash
cd frontend && pnpm test
```

预期：3 个用例通过。

**Step 3: Commit**

```bash
git add frontend/components/chat/ToolCallCard.tsx frontend/tests/ToolCallCard.test.tsx
git commit -m "refactor(frontend): adapt ToolCallCard to AG-UI status enum"
```

### Task 7: 重写对话区 ThreadArea（headless 消息列表 + 输入 + 工具卡 + 思考折叠 + 反馈）

**Files:**
- Modify: frontend/components/chat/ThreadArea.tsx（整体重写）

**Step 1: 写 headless ThreadArea**

```tsx
"use client";

import { useAgent, useCopilotKit, UseAgentUpdate } from "@copilotkit/react-core/v2";
import { useCallback, useEffect, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { AGENT_ID, useChatRuntime } from "./RuntimeProvider";
import ToolCallCard from "./ToolCallCard";
import { CodeBlock, InlineCode } from "./CodeHighlight";
import type { AgentMessage } from "@/lib/types";

// ———— 思考折叠 ————

function Reasoning({ text }: { text: string }) {
  if (!text) return null;
  return (
    <details className="mb-2 rounded-md border border-[color:var(--color-line-soft)] bg-[color:var(--color-bg-soft)] px-3 py-2">
      <summary className="cursor-pointer select-none text-[12px] text-[color:var(--color-ink-faint)]">
        思考过程（{text.length} 字）
      </summary>
      <p className="mt-1.5 whitespace-pre-wrap text-[12px] leading-relaxed text-[color:var(--color-ink-dim)]">
        {text}
      </p>
    </details>
  );
}

// ———— 反馈 ————

function FeedbackBar({ messageId }: { messageId: string }) {
  const [voted, setVoted] = useState<"positive" | "negative" | null>(null);
  useEffect(() => {
    try {
      const raw = localStorage.getItem("invest.feedback." + messageId);
      if (raw) setVoted((JSON.parse(raw) as { type: "positive" | "negative" }).type);
    } catch {
      // ignore
    }
  }, [messageId]);
  const vote = (type: "positive" | "negative") => {
    try {
      localStorage.setItem("invest.feedback." + messageId, JSON.stringify({ type, at: Date.now() }));
    } catch {
      // ignore
    }
    setVoted(type);
  };
  return (
    <div className="mt-1.5 flex items-center gap-1 opacity-35 transition-opacity hover:opacity-100">
      <button
        aria-label="回答有帮助"
        onClick={() => vote("positive")}
        className={
          "rounded px-1.5 py-0.5 text-[12px] transition-colors " +
          (voted === "positive" ? "text-[color:var(--color-down)]" : "text-[color:var(--color-ink-faint)] hover:text-[color:var(--color-down)]")
        }
      >
        👍
      </button>
      <button
        aria-label="回答需要改进"
        onClick={() => vote("negative")}
        className={
          "rounded px-1.5 py-0.5 text-[12px] transition-colors " +
          (voted === "negative" ? "text-[color:var(--color-up)]" : "text-[color:var(--color-ink-faint)] hover:text-[color:var(--color-up)]")
        }
      >
        👎
      </button>
    </div>
  );
}

// ———— 消息行 ————

function UserMessage({ content }: { content: string }) {
  return (
    <div className="animate-rise my-5 flex justify-end">
      <div className="max-w-[78%] rounded-xl rounded-tr-sm border border-[color:var(--color-line-soft)] bg-[color:var(--color-panel)] px-4 py-2.5 text-[14px] leading-relaxed text-[color:var(--color-ink)]">
        {content}
      </div>
    </div>
  );
}

function AssistantMessage({ message }: { message: AgentMessage }) {
  const toolCalls = message.toolCalls ?? [];
  const reasoning = message.reasoning;
  const content = typeof message.content === "string" ? message.content : "";

  return (
    <div className="animate-rise my-5 flex gap-3.5">
      <span className="mt-0.5 grid h-7 w-7 shrink-0 place-items-center rounded-md border border-[color:var(--color-line)] bg-[color:var(--color-panel)] text-[13px] text-[color:var(--color-up)]">
        砚
      </span>
      <div className="min-w-0 flex-1">
        {reasoning && <Reasoning text={reasoning} />}
        {toolCalls.map((tc) => (
          <ToolCallCard
            key={tc.id}
            toolCallId={tc.id}
            toolName={tc.name}
            argsText={tc.arguments ?? ""}
            result={tc.result}
            status={tc.status}
          />
        ))}
        {content && (
          <div className="md-body text-[14px] leading-relaxed text-[color:var(--color-ink)]">
            <ReactMarkdown
              remarkPlugins={[remarkGfm]}
              components={{
                pre: (p) => <>{p.children}</>,
                code: ({ className, children }) =>
                  /language-[\w-]+/.test(className ?? "") ? (
                    <CodeBlock className={className}>{children}</CodeBlock>
                  ) : (
                    <InlineCode>{children}</InlineCode>
                  ),
              }}
            >
              {content}
            </ReactMarkdown>
          </div>
        )}
        <FeedbackBar messageId={message.id} />
      </div>
    </div>
  );
}

// ———— 空状态 ————

const EXAMPLES = [
  "帮我看看贵州茅台最近的走势和估值",
  "今天大盘表现怎么样？",
  "搜索一下宁德时代最近的新闻",
];

function EmptyState({
  llmReady,
  onPick,
}: {
  llmReady: boolean | null;
  onPick: (prompt: string) => void;
}) {
  return (
    <div className="animate-rise flex flex-col items-center pt-[10vh] text-center">
      <p className="font-[family-name:var(--font-display)] text-[26px] leading-snug tracking-wide text-[color:var(--color-ink)]">
        问行情 · 看走势 · 读财报
      </p>
      <p className="mt-3 max-w-md text-[13px] leading-relaxed text-[color:var(--color-ink-dim)]">
        基于实时行情数据与 DeepSeek 大模型的 A股投研助手。
        Agent 会自动调用行情、财务与新闻工具，为你整理数据并给出分析。
      </p>
      {llmReady === false && (
        <p className="mt-5 max-w-md rounded-lg border border-[color:var(--color-amber)]/40 bg-[color:var(--color-panel)] px-4 py-3 text-[12px] leading-relaxed text-[color:var(--color-amber)]">
          未检测到 DEEPSEEK_API_KEY：对话功能暂不可用。请在 backend 的 .env
          中配置后重启服务；行情数据页仍可正常浏览。
        </p>
      )}
      <div className="mt-8 flex flex-wrap justify-center gap-2.5">
        {EXAMPLES.map((prompt) => (
          <button
            key={prompt}
            onClick={() => onPick(prompt)}
            className="rounded-full border border-[color:var(--color-line)] bg-[color:var(--color-panel)] px-4 py-2 text-[13px] text-[color:var(--color-ink-dim)] transition-all hover:border-[color:var(--color-up)] hover:text-[color:var(--color-ink)]"
          >
            {prompt}
          </button>
        ))}
      </div>
    </div>
  );
}

// ———— 输入区 ————

function Composer({
  isRunning,
  onSend,
  onStop,
}: {
  isRunning: boolean;
  onSend: (text: string) => void;
  onStop: () => void;
}) {
  const [draft, setDraft] = useState("");
  const submit = () => {
    const t = draft.trim();
    if (!t || isRunning) return;
    setDraft("");
    onSend(t);
  };
  return (
    <div className="border-t border-[color:var(--color-line)] bg-[color:var(--color-bg)]/80 px-4 pb-4 pt-3 backdrop-blur-sm">
      <div className="composer mx-auto max-w-[860px] p-2">
        <textarea
          rows={1}
          autoFocus
          value={draft}
          placeholder="问行情、看走势、读财报… 例如：帮我看看贵州茅台最近的走势和估值"
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.shiftKey) {
              e.preventDefault();
              submit();
            }
          }}
          onInput={(e) => {
            const el = e.currentTarget;
            el.style.height = "auto";
            el.style.height = Math.min(el.scrollHeight, 160) + "px";
          }}
          className="max-h-40 w-full resize-none bg-transparent px-2.5 py-1.5 text-[14px] leading-relaxed text-[color:var(--color-ink)] placeholder:text-[color:var(--color-ink-faint)] focus:outline-none"
        />
        <div className="flex items-center justify-between px-1.5 pb-0.5 pt-1.5">
          <span className="text-[11px] text-[color:var(--color-ink-faint)]">
            Enter 发送 · Shift+Enter 换行 · 回答由 DeepSeek 生成
          </span>
          {isRunning ? (
            <button
              onClick={onStop}
              className="rounded-md border border-[color:var(--color-line)] px-3 py-1 text-[12px] text-[color:var(--color-ink-dim)] transition-colors hover:border-[color:var(--color-up)] hover:text-[color:var(--color-up)]"
            >
              ■ 停止
            </button>
          ) : (
            <button
              onClick={submit}
              disabled={!draft.trim()}
              className="rounded-md bg-[color:var(--color-up)] px-4 py-1 text-[12px] text-white transition-all enabled:hover:brightness-110 disabled:opacity-30"
            >
              发送
            </button>
          )}
        </div>
      </div>
    </div>
  );
}

// ———— 会话区 ————

export default function ThreadArea({ llmReady }: { llmReady: boolean | null }) {
  const { currentThreadId } = useChatRuntime();
  const { agent } = useAgent({
    agentId: AGENT_ID,
    threadId: currentThreadId,
    updates: [UseAgentUpdate.OnMessagesChanged, UseAgentUpdate.OnRunStatusChanged],
  });
  const { copilotkit } = useCopilotKit();

  const send = useCallback(
    async (text: string) => {
      const t = text.trim();
      if (!t || agent.isRunning) return;
      agent.addMessage({ id: crypto.randomUUID(), role: "user", content: t });
      await copilotkit.runAgent({ agent });
    },
    [agent, copilotkit],
  );

  const messages = agent.messages ?? [];
  const isEmpty = messages.length === 0;

  return (
    <div className="flex h-full min-w-0 flex-1 flex-col">
      <div className="flex-1 overflow-y-auto">
        <div className="mx-auto max-w-[860px] px-5 py-8">
          {isEmpty ? (
            <EmptyState llmReady={llmReady} onPick={(p) => void send(p)} />
          ) : (
            messages.map((m: AgentMessage) =>
              m.role === "user" ? (
                <UserMessage key={m.id} content={String(m.content ?? "")} />
              ) : (
                <AssistantMessage key={m.id} message={m} />
              ),
            )
          )}
          <div className="h-6" />
        </div>
      </div>
      <Composer
        isRunning={agent.isRunning}
        onSend={(t) => void send(t)}
        onStop={() => agent.abortRun()}
      />
    </div>
  );
}
```

**Step 2: 形态验证（关键——确认 reasoning / toolCalls 字段与流式）**

```bash
# 后端 + 前端已启动（make dev），浏览器打开 http://localhost:3000
# 输入 "搜索一下宁德时代最近的新闻"，观察：
# 1) 思考过程 <details> 是否出现（message.reasoning 非空）
# 2) 工具卡是否出现并脉冲（message.toolCalls 数组被填充）
# 3) 文本是否流式输出、停止按钮是否生效
```

预期：三者正常。若 message.reasoning 字段名与实现不符（后端 enable-reasoning: true 下应发 REASONING 事件），打开浏览器 Network 查看 /api/copilotkit/agent/invest/run 的 SSE 事件与 agent.messages 结构，据实修正字段名后再提交。

**Step 3: Commit**

```bash
git add frontend/components/chat/ThreadArea.tsx
git commit -m "feat(frontend): headless CopilotKit thread area (messages/tools/reasoning/feedback)"
```

### Task 8: 重写侧边栏 Sidebar（自定义会话列表）

**Files:**
- Modify: frontend/components/chat/Sidebar.tsx（整体重写）

**Step 1: 写自定义会话列表**

```tsx
"use client";

import { useChatRuntime } from "./RuntimeProvider";

function timeAgo(ts: number): string {
  const diff = Date.now() - ts;
  const min = Math.floor(diff / 60000);
  if (min < 1) return "刚刚";
  if (min < 60) return min + " 分钟前";
  const h = Math.floor(min / 60);
  if (h < 24) return h + " 小时前";
  return Math.floor(h / 24) + " 天前";
}

export default function Sidebar({
  health,
}: {
  health: { llmKey: boolean; marketOk: boolean } | null;
}) {
  const { sessions, currentThreadId, newThread, switchThread, deleteThread } =
    useChatRuntime();

  return (
    <aside className="flex h-full w-64 shrink-0 flex-col border-r border-[color:var(--color-line)] bg-[color:var(--color-bg-soft)]/60">
      <div className="flex min-h-0 flex-1 flex-col">
        <div className="p-3">
          <button
            onClick={newThread}
            className="w-full rounded-lg border border-[color:var(--color-line)] bg-[color:var(--color-panel)] px-3 py-2 text-[13px] text-[color:var(--color-ink)] transition-all hover:border-[color:var(--color-up)] hover:shadow-[var(--shadow-glow)]"
          >
            ＋ 新对话
          </button>
        </div>

        <nav className="flex-1 overflow-y-auto px-2 pb-3">
          {sessions.length === 0 && (
            <p className="px-3 pt-8 text-center text-[12px] leading-relaxed text-[color:var(--color-ink-faint)]">
              还没有会话
              <br />
              从一句提问开始吧
            </p>
          )}
          <ul>
            {sessions.map((s) => {
              const active = s.id === currentThreadId;
              return (
                <li key={s.id} className="group relative">
                  <button
                    onClick={() => switchThread(s.id)}
                    className={
                      "w-full rounded-lg px-3 py-2 text-left transition-colors " +
                      (active
                        ? "bg-[color:var(--color-panel-2)] text-[color:var(--color-ink)]"
                        : "text-[color:var(--color-ink-dim)] hover:bg-[color:var(--color-panel)] hover:text-[color:var(--color-ink)]")
                    }
                  >
                    <span className="block truncate text-[13px]">{s.title}</span>
                    <span className="mt-0.5 block text-[11px] text-[color:var(--color-ink-faint)]">
                      {timeAgo(s.updatedAt)}
                    </span>
                  </button>
                  <button
                    onClick={() => deleteThread(s.id)}
                    aria-label="删除会话"
                    className="absolute right-1.5 top-1/2 hidden -translate-y-1/2 rounded p-1 text-[color:var(--color-ink-faint)] hover:text-[color:var(--color-up)] group-hover:block"
                  >
                    ✕
                  </button>
                </li>
              );
            })}
          </ul>
        </nav>
      </div>

      <footer className="border-t border-[color:var(--color-line-soft)] p-3 text-[11px] leading-relaxed text-[color:var(--color-ink-faint)]">
        <div className="mb-2 flex items-center gap-2">
          <span
            className={
              "h-1.5 w-1.5 rounded-full " +
              (health
                ? health.llmKey && health.marketOk
                  ? "bg-[color:var(--color-down)]"
                  : "bg-[color:var(--color-amber)]"
                : "bg-[color:var(--color-ink-faint)]")
            }
          />
          <span>
            {health
              ? health.llmKey && health.marketOk
                ? "系统就绪"
                : health.llmKey
                  ? "行情源异常"
                  : "未配置模型 Key"
              : "连接中…"}
          </span>
        </div>
        <p>数据来自公开行情接口，仅供参考，不构成投资建议。</p>
      </footer>
    </aside>
  );
}
```

**Step 2: 验证** — 浏览器验证"新对话/切换/删除"三个动作与会话列表刷新。

**Step 3: Commit**

```bash
git add frontend/components/chat/Sidebar.tsx
git commit -m "refactor(frontend): custom session sidebar on useChatRuntime"
```

### Task 9: 清理死代码与文档

**Files:**
- Delete: frontend/app/api/chat/route.ts
- Delete: frontend/lib/agui.ts
- Delete: frontend/tests/agui.test.ts
- Modify: README.md（技术栈行 assistant-ui → CopilotKit）
- Create: docs/technology/decisions/0006-frontend-copilotkit.md

**Step 1: 删除 dead code**

```bash
rm frontend/app/api/chat/route.ts frontend/lib/agui.ts frontend/tests/agui.test.ts
```

（runAgent 已无调用方；parseSseBlock 仅被该测试引用；SSE 解析现由 CopilotKit 承担。）

**Step 2: 更新 README 技术栈行**

README.md 第 14 行与技术栈列表：把 assistant-ui（AG-UI 前端） 改为 CopilotKit（AG-UI 前端）。

**Step 3: 写 ADR-0006**

```markdown
# ADR-0006 前端 AG-UI 框架切换：assistant-ui → CopilotKit

- 状态：已接受（2026-08-19）
- 决策者：项目负责人
- 取代：ADR-0005（前端 AG-UI 框架选型）

## 背景

ADR-0005 选择 assistant-ui 作为前端 AG-UI 渲染层。实测后发现问题：
- assistant-ui 0.x API 变动频繁（v0.15 Aui 架构与 0.14 差异大），升级成本高；
- 其 AG-UI 适配（@assistant-ui/react-ag-ui）0.x 与核心包耦合紧，锁版本后仍难跟上游；
- 项目自绘的"研报终端"设计体系与 headless 原语贴合，但多套原语（Thread/Message/Composer/ActionBar）
  带来的心智与维护成本偏高。

CopilotKit 1.68.1（v2）是 AG-UI 协议的缔造者与主维护方，v2 API 已稳定：
- 官方 CopilotRuntime 服务端运行时 + HttpAgent(@ag-ui/client) 直接对接任意 AG-UI 端点；
- headless useAgent/useCopilotKit 可完整自绘 UI，保留既有视觉；
- 与后端 AgentScope 的 AG-UI 端点零耦合（后端零改动）。

## 决策

前端 AG-UI 渲染层整体切换为 **CopilotKit v2（@copilotkit/react-core + @copilotkit/runtime，1.68.1）**：
运行时路由 /api/copilotkit → HttpAgent → 后端 POST /agui/run；前端 useAgent headless 自绘。
会话模型（ADR-0004 前端持有历史）与交互协议（ADR-0002 AG-UI）不变。

## 后果

正面：跟随 AG-UI 官方生态主线；headless 能力完整保留设计体系；移除 @assistant-ui 三件套与手写 SSE 解析。
风险：CopilotKit v1 多次 breaking、v2 是新面 → 全部走 /v2 子路径并锁 1.68.1；
reasoning/toolCalls 消息字段形态需实测确认（Task 7 设验证步）。
```

**Step 4: Commit**

```bash
git add -A frontend/app/api/chat frontend/lib/agui.ts frontend/tests/agui.test.ts README.md docs/technology/decisions/0006-frontend-copilotkit.md
git commit -m "chore(frontend): remove assistant-ui dead code, add ADR-0006"
```

### Task 10: 全量验证与收尾

**Step 1: 单测**

```bash
cd frontend && pnpm test
```

预期：仅剩 ToolCallCard.test.tsx 通过（原 agui.test.ts 已删）。

**Step 2: 构建**

```bash
cd frontend && pnpm build
```

预期：构建通过，无 @assistant-ui/* 残留 import（grep -r "assistant-ui" frontend --include="*.ts*" --exclude-dir=node_modules 应为空）。

**Step 3: 端到端冒烟（真实后端 + 行情接口）**

```bash
make dev   # 或分别启动 backend/frontend
# 浏览器 http://localhost:3000：
# 1) 首页空态 → 点击示例问题 → 流式回答 + 思考折叠 + 工具卡
# 2) 问 "贵州茅台财务指标" → 多个工具卡依次出现并完成
# 3) 停止按钮中断流式
# 4) 新对话/切换会话/删除会话；刷新页面后历史保留（localStorage）
# 5) 👍/👎 反馈落 localStorage
# 6) /market 行情台不受影响
```

预期：全部通过。

**Step 4: Commit（如有冒烟期间的小修）**

```bash
git add -A && git commit -m "fix(frontend): CopilotKit migration smoke fixes"
```

---

# 三、验收清单

- [ ] grep -r "assistant-ui" frontend --include="*.ts*" --exclude-dir=node_modules 无结果
- [ ] pnpm test、pnpm build 通过
- [ ] 流式文本、思考折叠、工具卡（6 类中文标签）、代码高亮/复制、停止、反馈、会话增删改查、历史持久化、行情台均正常
- [ ] 后端零改动（未触碰 backend/）
- [ ] ADR-0006 与 README 已更新

