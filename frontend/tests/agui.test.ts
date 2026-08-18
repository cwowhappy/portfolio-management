import { describe, expect, it } from "vitest";
import { parseSseBlock } from "../lib/agui";

describe("parseSseBlock", () => {
  it("解析文本增量事件", () => {
    const e = parseSseBlock('event:TEXT_MESSAGE_CONTENT\ndata:{"delta":"你好"}\n\n');
    expect(e).not.toBeNull();
    expect(e!.type).toBe("TEXT_MESSAGE_CONTENT");
    expect(e!.delta).toBe("你好");
  });

  it("多行 data 合并后再解析（分片位于 JSON token 边界）", () => {
    const block = 'event:CUSTOM\ndata:{"name":"token_usage",\ndata:"value":123}\n\n';
    const e = parseSseBlock(block);
    expect(e!.name).toBe("token_usage");
    expect(e!.value).toBe(123);
  });

  it("工具调用事件携带 toolCallId 与名称", () => {
    const e = parseSseBlock(
      'event:TOOL_CALL_START\ndata:{"toolCallId":"c1","toolCallName":"get_quote"}\n\n',
    );
    expect(e!.toolCallId).toBe("c1");
    expect(e!.toolCallName).toBe("get_quote");
  });

  it("无 data 行返回 null", () => {
    expect(parseSseBlock("event:HEARTBEAT\n\n")).toBeNull();
  });

  it("非法 JSON 返回 null", () => {
    expect(parseSseBlock("event:RUN_STARTED\ndata:{bad json\n\n")).toBeNull();
  });
});
