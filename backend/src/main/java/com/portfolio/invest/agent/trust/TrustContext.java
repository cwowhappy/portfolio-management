package com.portfolio.invest.agent.trust;

import io.agentscope.core.agent.RuntimeContext;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 真值池上下文（MS-29 B3，设计规格 §三）：一次 Agent 回合内累积的工具真值
 * {@link ToolInvocation}，供 B5 hook 在收尾锚定/校验时消费。
 *
 * <p><strong>双通道取用（顺序：RuntimeContext 优先、ThreadLocal 兜底）：</strong>
 * <ul>
 *   <li>主通道：{@code RuntimeContext} typed put/get（键 {@code TrustContext.class}）——
 *       与工具调用同上下文可见，天然按回合隔离；</li>
 *   <li>回退通道：ThreadLocal（{@code CurrentUserHolder} 先例风格）——供拿不到
 *       RuntimeContext 的调用方兜底。</li>
 * </ul>
 * 两通道皆空时新建并<strong>双挂</strong>（rc 与 ThreadLocal 都写入，回合内任一通道取到同一池，
 * 且覆盖可能残留的旧值）。ThreadLocal 残留由 {@link #reset()} 清理——回合收尾必须调用
 * （B5 接线；池化线程复用防跨回合串值），未清理时 rc 通道仍按回合隔离、只有兜底通道可能读到旧池。
 */
public final class TrustContext {

    private static final ThreadLocal<TrustContext> FALLBACK = new ThreadLocal<>();

    private final List<ToolInvocation> invocations = new CopyOnWriteArrayList<>();

    private TrustContext() {}

    /**
     * 取当前回合真值池：RuntimeContext 优先、ThreadLocal 兜底；两通道皆空则新建并双挂。
     * {@code runtimeContext} 为 null 时只走兜底通道；rc 在而池未挂时<strong>新建</strong>（不收养
     * 兜底残留——防忘 {@link #reset()} 时跨回合串池；rebuild 场景的回合内连续性由 B5 回合首
     * 预挂池保证）。
     */
    public static TrustContext current(RuntimeContext runtimeContext) {
        if (runtimeContext != null) {
            TrustContext attached = runtimeContext.get(TrustContext.class);
            if (attached != null) {
                return attached;
            }
        }
        TrustContext fallback = FALLBACK.get();
        if (fallback != null && runtimeContext == null) {
            return fallback;
        }
        TrustContext created = new TrustContext();
        if (runtimeContext != null) {
            runtimeContext.put(TrustContext.class, created);
        }
        FALLBACK.set(created);
        return created;
    }

    /** 清理 ThreadLocal 回退通道（回合收尾调用；CurrentUserHolder 先例）。 */
    public static void reset() {
        FALLBACK.remove();
    }

    /**
     * 回合开始强制预挂<strong>新池</strong>（MS-29 B5，B3 报告接线建议）：覆盖 rc 上可能残留的旧池
     * 并同步 ThreadLocal——回合内工具调用与 hook 经 {@link #current} 命中同一池。
     * 包私有：仅 trust 包内回合边界（hook PRE_CALL）调用。
     */
    static TrustContext fresh(RuntimeContext runtimeContext) {
        TrustContext created = new TrustContext();
        if (runtimeContext != null) {
            runtimeContext.put(TrustContext.class, created);
        }
        FALLBACK.set(created);
        return created;
    }

    /** 只读快照（B5 hook 消费）。 */
    public List<ToolInvocation> invocations() {
        return List.copyOf(invocations);
    }

    /** 记录一次工具调用真值（trust 包内装饰器独占写权）。null 静默跳过。 */
    void record(ToolInvocation invocation) {
        if (invocation != null) {
            invocations.add(invocation);
        }
    }
}
