#!/usr/bin/env node
// e2e 专用最小 MCP server（Streamable HTTP，JSON 响应形态）：write_note 无 annotations（触发审批），
// read_note readOnlyHint=true（放行）。与后端 McpClientPool 的 streamableHttpTransport 客户端对齐。
import { createServer } from "node:http";
import { appendFileSync, readFileSync, existsSync } from "node:fs";

const PORT = 8765;
const NOTES = process.env.HITL_E2E_NOTES ?? "/tmp/hitl-e2e-notes.log";
const PROTOCOL = "2025-06-18";

const TOOLS = [
  {
    name: "write_note",
    description: "把一段内容追加写入笔记（写工具，触发审批）",
    inputSchema: { type: "object", properties: { content: { type: "string" } }, required: ["content"] },
  },
  {
    name: "read_note",
    description: "读取当前笔记内容（只读工具，不应触发审批）",
    inputSchema: { type: "object", properties: {} },
    annotations: { readOnlyHint: true },
  },
];

const json = (res, body) => {
  res.writeHead(200, { "Content-Type": "application/json", "Mcp-Session-Id": "e2e-session" });
  res.end(JSON.stringify(body));
};

createServer((req, res) => {
  if (req.method === "GET") {
    // webServer 探活 + streamable GET 通道占位：直接 200
    res.writeHead(200, { "Content-Type": "text/event-stream" });
    res.end();
    return;
  }
  let raw = "";
  req.on("data", (c) => (raw += c));
  req.on("end", () => {
    let msg;
    try { msg = JSON.parse(raw); } catch { json(res, { jsonrpc: "2.0", id: null, error: { code: -32700, message: "Parse error" } }); return; }
    const { id, method, params } = msg;
    if (method === "initialize") {
      json(res, { jsonrpc: "2.0", id, result: { protocolVersion: params?.protocolVersion ?? PROTOCOL, capabilities: { tools: { listChanged: false } }, serverInfo: { name: "hitl-e2e", version: "1.0.0" } } });
    } else if (method === "notifications/initialized") {
      res.writeHead(202); res.end();
    } else if (method === "tools/list") {
      json(res, { jsonrpc: "2.0", id, result: { tools: TOOLS } });
    } else if (method === "tools/call") {
      const name = params?.name;
      if (name === "write_note") {
        const content = params?.arguments?.content ?? "";
        appendFileSync(NOTES, content + "\n");
        json(res, { jsonrpc: "2.0", id, result: { content: [{ type: "text", text: `已写入：${content}` }] } });
      } else if (name === "read_note") {
        const text = existsSync(NOTES) ? readFileSync(NOTES, "utf8") : "(空)";
        json(res, { jsonrpc: "2.0", id, result: { content: [{ type: "text", text }] } });
      } else {
        json(res, { jsonrpc: "2.0", id, error: { code: -32602, message: `Unknown tool: ${name}` } });
      }
    } else {
      json(res, { jsonrpc: "2.0", id, error: { code: -32601, message: `Method not found: ${method}` } });
    }
  });
}).listen(PORT, "127.0.0.1", () => console.log(`hitl-e2e MCP server on http://127.0.0.1:${PORT}/mcp`));
