"use client";
// flush 持久化 hook（P4-F3 自 ThreadArea 抽取）：debounce 400ms + 三处 flush
// （防抖到期 / 运行停止立即 / 卸载切线程 keepalive）+ 409 合并重试是一完整内聚语义。
// 并发序号守卫、切线程/回灌双保险与全部注释原样搬移，零行为变更。

import { useCallback, useEffect, useRef } from "react";
import type { Message } from "@ag-ui/client";
import type { ChatMessage } from "@/lib/types";
import { agentMessagesToHistory } from "./RuntimeProvider";
import { errorStatus, loadMessages } from "@/lib/conversations";

type PersistMessagesFn = (
  threadId: string,
  msgs: ChatMessage[],
  opts?: { keepalive?: boolean; ifMatch?: string },
) => Promise<void>;

interface UsePersistFlushArgs {
  currentThreadId: string;
  /** agent.messages 本体（调用侧 ?? [] 前的原始值，hook 内快照时兜底） */
  messages: Message[] | undefined;
  isRunning: boolean;
  /** 当前 agent.messages 内容归属的线程（ThreadArea 回灌 effect 维护）：防抖启动闸门 */
  hydratedThreadIdRef: React.RefObject<string | null>;
  persistMessages: PersistMessagesFn;
  /** 409 重试仍冲突时的透出回调（ThreadArea 渲染错误横幅） */
  onMergeConflict: () => void;
}

export function usePersistFlush({
  currentThreadId,
  messages,
  isRunning,
  hydratedThreadIdRef,
  persistMessages,
  onMergeConflict,
}: UsePersistFlushArgs): void {
  const persistTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  // 防抖窗口内待写入的快照：供「运行停止立即 flush」与「卸载/切线程 best-effort flush」使用
  const pendingPersist = useRef<{ threadId: string; msgs: Message[] } | null>(null);
  // flushPersist 并发序号（P2-F3，按线程隔离）：每次发起 flush 为该线程自增。运行停止
  // flush 与防抖/keepalive flush 重叠时各自 async IIFE 并发，先发起者的 PUT 可能后完成、
  // 以旧快照覆盖新快照——后发起的 flush 代表更新的消息快照；旧的让其自然作废（新快照会
  // 带走内容）。必须按 threadId 隔离：跨线程 PUT 按 URL 隔离本就安全，全局序号会误杀旧
  // 线程在途 flush——它携带的尾部内容只有它自己能写，丢弃后切回即被服务端记录覆盖（审查发现）。
  const flushSeqByThreadRef = useRef(new Map<string, number>());
  // 最新 currentThreadId：供防抖定时器到期 / 运行停止 flush 前校验 pending 是否已过期（双保险）
  const currentThreadIdRef = useRef(currentThreadId);

  // 同步最新线程到 ref，供异步 flush 前比对 pending.threadId
  useEffect(() => {
    currentThreadIdRef.current = currentThreadId;
  }, [currentThreadId]);

  // 执行一次持久化。msgs 是调用点快照：若等待 loadMessages 期间发生切线程 + 历史回灌
  // setMessages，agent.messages 会变成新线程的，若不快照会把新线程消息写进旧线程记录。
  // keepalive 用于卸载场景：让 PUT 在页面销毁后仍能完成。
  // B6 并发写：首次 PUT 携带 If-Match（GET 到的服务端 updatedAt）做乐观校验；
  // 撞 409（其他窗口先写）→ 重新 GET 服务端消息，与本地快照按 id 做 union 后带新 If-Match
  // 重试一次（agentMessagesToHistory 的 union 语义保证不互删）；重试仍 409 则提示用户刷新
  // 确认——本地草稿消息保持不动，刷新后以服务端为准人工合并。
  const flushPersist = useCallback(
    (threadId: string, msgs: Message[], keepalive = false) => {
      // 并发序号守卫（P2-F3）：为本线程登记快照序号，IIFE 内每个 await 之后、发起写之前
      // 校验自己仍是该线程最新——后发起的 flush 代表更新的消息快照；旧的让其自然作废。
      const seq = (flushSeqByThreadRef.current.get(threadId) ?? 0) + 1;
      flushSeqByThreadRef.current.set(threadId, seq);
      (async () => {
        try {
          const view = await loadMessages(threadId);
          // 已有更新的 flush 发起：放弃本快照（新快照会带走内容），不再发起写
          if (seq !== (flushSeqByThreadRef.current.get(threadId) ?? 0)) {
            console.debug("[usePersistFlush] 过期 flush 丢弃", threadId, seq);
            return;
          }
          const saved = agentMessagesToHistory(msgs, view.messages);
          if (saved.length === 0) return;
          try {
            await persistMessages(threadId, saved, { keepalive, ifMatch: view.updatedAt });
            return;
          } catch (e) {
            if (errorStatus(e) !== 409) throw e;
          }
          const refreshed = await loadMessages(threadId);
          // 重试 PUT 前同样校验：已有更新的 flush 发起即放弃，新快照会带走内容
          if (seq !== (flushSeqByThreadRef.current.get(threadId) ?? 0)) {
            console.debug("[usePersistFlush] 过期 flush 丢弃", threadId, seq);
            return;
          }
          const merged = agentMessagesToHistory(msgs, refreshed.messages);
          try {
            await persistMessages(threadId, merged, { keepalive, ifMatch: refreshed.updatedAt });
          } catch (retryErr) {
            if (errorStatus(retryErr) !== 409) throw retryErr;
            onMergeConflict();
          }
        } catch (e) {
          console.error("[usePersistFlush] 持久化会话失败", threadId, e);
        }
      })();
    },
    [persistMessages, onMergeConflict],
  );

  // 消息变化 → 防抖持久化（流式期间高频触发，避免每个 token 都整段重写；400ms 后先拉取现有历史再整体 PUT）
  useEffect(() => {
    // 双保险 (1)：切线程/历史回灌完成前，agent.messages 仍是旧线程内容，不启动新线程的防抖持久化，
    // 否则 400ms 后 flushPersist(newThreadId, oldMsgs) 会把旧会话内容 PUT 覆盖新线程服务端记录。
    const hydrated = hydratedThreadIdRef.current;
    if (hydrated !== null && hydrated !== currentThreadId) {
      pendingPersist.current = null;
      return;
    }
    pendingPersist.current = { threadId: currentThreadId, msgs: messages ?? [] };
    if (persistTimer.current) clearTimeout(persistTimer.current);
    persistTimer.current = setTimeout(() => {
      persistTimer.current = null;
      const pending = pendingPersist.current;
      pendingPersist.current = null;
      // 双保险 (2)：防抖到期时线程已切换（pending.threadId 落后于 currentThreadId）则丢弃快照。
      // 旧线程快照的落库由「卸载/切线程 cleanup」以 keepalive flush 负责。
      if (pending && pending.threadId === currentThreadIdRef.current) {
        flushPersist(pending.threadId, pending.msgs);
      }
    }, 400);
    return () => {
      if (persistTimer.current) {
        clearTimeout(persistTimer.current);
        persistTimer.current = null;
      }
    };
  }, [messages, currentThreadId, hydratedThreadIdRef, flushPersist]);

  // 运行结束（isRunning true→false）立即 flush：防抖窗口内的尾部内容不等 400ms，避免停止即丢尾
  const prevRunning = useRef(isRunning);
  useEffect(() => {
    const wasRunning = prevRunning.current;
    prevRunning.current = isRunning;
    if (!wasRunning || isRunning) return;
    if (persistTimer.current) {
      clearTimeout(persistTimer.current);
      persistTimer.current = null;
    }
    const pending = pendingPersist.current;
    pendingPersist.current = null;
    // 运行期间 RuntimeProvider 已禁止切线程，此处校验 pending.threadId === 当前线程为双保险
    if (pending && pending.threadId === currentThreadIdRef.current) {
      flushPersist(pending.threadId, pending.msgs);
    }
  }, [isRunning, flushPersist]);

  // 卸载/切换线程时，把仍在防抖窗口内的 pending 写入 best-effort flush（keepalive 保证请求送达）。
  // 声明在防抖 effect 之后：cleanup 按声明顺序执行，先停掉旧定时器，再 flush 旧线程快照。
  useEffect(() => {
    return () => {
      const pending = pendingPersist.current;
      if (pending && pending.threadId === currentThreadId) {
        pendingPersist.current = null;
        flushPersist(pending.threadId, pending.msgs, true);
      }
    };
  }, [currentThreadId, flushPersist]);
}
