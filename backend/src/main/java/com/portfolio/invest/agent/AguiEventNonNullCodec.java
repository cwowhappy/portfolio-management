package com.portfolio.invest.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.util.JsonCodec;
import java.lang.reflect.Type;

/**
 * AG-UI 事件非空序列化 codec（#25）：agentscope 权限确认中断的 expiresAt 序列化为 null，
 * 而前端 @ag-ui/core 0.0.59 的 InterruptSchema 只认 缺省/字符串，null 会让整条 RUN_FINISHED
 * 被浏览器端 zod 拒收（审批卡片不渲染）。本 codec 仅对 AguiEvent 的 toJson 剥离 null 字段
 * （null→缺省，前端语义等价），其余序列化/反序列化全部委托默认实现——LLM 请求体、stateStore、
 * 工具参数的字节行为不变。上游对齐后（@ag-ui/core 收 null 或 agentscope 发缺省）回收本类。
 */
public class AguiEventNonNullCodec implements JsonCodec {

    private final JsonCodec delegate;
    private final JsonCodec aguiEventCodec;

    public AguiEventNonNullCodec(JsonCodec delegate, JsonCodec aguiEventCodec) {
        this.delegate = delegate;
        this.aguiEventCodec = aguiEventCodec;
    }

    @Override
    public String toJson(Object value) {
        return (value instanceof AguiEvent ? aguiEventCodec : delegate).toJson(value);
    }

    @Override
    public String toPrettyJson(Object value) {
        return delegate.toPrettyJson(value);
    }

    @Override
    public <T> T fromJson(String json, Class<T> type) {
        return delegate.fromJson(json, type);
    }

    @Override
    public <T> T fromJson(String json, TypeReference<T> type) {
        return delegate.fromJson(json, type);
    }

    @Override
    public <T> T convertValue(Object value, Class<T> type) {
        return delegate.convertValue(value, type);
    }

    @Override
    public <T> T convertValue(Object value, TypeReference<T> type) {
        return delegate.convertValue(value, type);
    }

    @Override
    public Object convertValue(Object value, Type type) {
        return delegate.convertValue(value, type);
    }
}
