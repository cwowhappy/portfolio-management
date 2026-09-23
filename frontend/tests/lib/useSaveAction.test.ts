import { afterEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, renderHook } from "@testing-library/react";
import { useSaveAction } from "@/lib/useSaveAction";

afterEach(() => cleanup());

/** 手动完成的 deferred：把 in-flight 窗口拿在测试手里。 */
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => { resolve = res; });
  return { promise, resolve };
}

describe("useSaveAction", () => {
  it("执行 action，期间 saving=true，完成后复位 false 且 error 保持 null", async () => {
    const gate = deferred<void>();
    const action = vi.fn(() => gate.promise);
    const { result } = renderHook(() => useSaveAction());

    let running!: Promise<void>;
    await act(async () => { running = result.current.run(action); });
    expect(result.current.saving).toBe(true);
    expect(action).toHaveBeenCalledOnce();

    await act(async () => { gate.resolve(); await running; });
    expect(result.current.saving).toBe(false);
    expect(result.current.error).toBeNull();
  });

  it("in-flight 期间再次 run 被忽略（action 不重复执行）", async () => {
    const gate = deferred<void>();
    const action = vi.fn(() => gate.promise);
    const { result } = renderHook(() => useSaveAction());

    let first!: Promise<void>;
    let second!: Promise<void>;
    await act(async () => {
      first = result.current.run(action);
      second = result.current.run(action); // 同一引用重入——闭包陈旧也须拦住
    });
    await act(async () => { gate.resolve(); await Promise.all([first, second]); });
    expect(action).toHaveBeenCalledOnce();
    expect(result.current.saving).toBe(false);
  });

  it("action 抛 Error：error 显示 e.message，saving 复位", async () => {
    const { result } = renderHook(() => useSaveAction());
    let running!: Promise<void>;
    await act(async () => {
      running = result.current.run(() => Promise.reject(new Error("网络中断")));
    });
    await act(async () => { await running; });
    expect(result.current.error).toBe("网络中断");
    expect(result.current.saving).toBe(false);
  });

  it("action 抛非 Error：error 显示默认兜底文案", async () => {
    const { result } = renderHook(() => useSaveAction());
    let running!: Promise<void>;
    await act(async () => { running = result.current.run(() => Promise.reject("boom")); });
    await act(async () => { await running; });
    expect(result.current.error).toBe("保存失败");
  });

  it("自定义兜底文案生效", async () => {
    const { result } = renderHook(() => useSaveAction("操作失败"));
    let running!: Promise<void>;
    await act(async () => { running = result.current.run(() => Promise.reject(undefined)); });
    await act(async () => { await running; });
    expect(result.current.error).toBe("操作失败");
  });

  it("新一次 run 开始时清除上一次的 error", async () => {
    const { result } = renderHook(() => useSaveAction());
    let failed!: Promise<void>;
    await act(async () => { failed = result.current.run(() => Promise.reject(new Error("第一次失败"))); });
    await act(async () => { await failed; });
    expect(result.current.error).toBe("第一次失败");

    let ok!: Promise<void>;
    await act(async () => { ok = result.current.run(() => Promise.resolve()); });
    await act(async () => { await ok; });
    expect(result.current.error).toBeNull();
  });

  it("reset 清除 saving 与 error（对话框重开防迟到状态翻转）", async () => {
    const { result } = renderHook(() => useSaveAction());
    let failed!: Promise<void>;
    await act(async () => { failed = result.current.run(() => Promise.reject(new Error("旧错误"))); });
    await act(async () => { await failed; });
    expect(result.current.error).toBe("旧错误");

    act(() => result.current.reset());

    expect(result.current.error).toBeNull();
    expect(result.current.saving).toBe(false);
  });

  it("网络 TypeError（fetch 失败）显示中文兜底而非英文 message", async () => {
    const { result } = renderHook(() => useSaveAction());
    let running!: Promise<void>;
    await act(async () => { running = result.current.run(() => Promise.reject(new TypeError("Failed to fetch"))); });
    await act(async () => { await running; });
    expect(result.current.error).toBe("保存失败");
  });
});
