import { joinSegments, relay } from "@/lib/proxy";

export const dynamic = "force-dynamic";

// ctx.params 段已解码：经 joinSegments 逐段重编码（防 %2F/%3F 注入分隔符）并拒 `..`（防逃前缀）
async function resolve(req: Request, ctx: { params: Promise<{ path?: string[] }> }) {
  const { path = [] } = await ctx.params;
  const joined = joinSegments("/api/memory", path);
  return joined === null ? null : joined + new URL(req.url).search;
}

export async function GET(req: Request, ctx: { params: Promise<{ path?: string[] }> }) {
  const joined = await resolve(req, ctx);
  if (!joined) return Response.json({ message: "非法路径" }, { status: 400 });
  return relay(joined, "GET", req);
}
// DELETE 带 JSON body（乐观并发入参 expectLine/baseHash 在请求体）：relay 的 body 分支
// 同样适用于 DELETE——fetch 允许 DELETE 携 body，透传时补 Content-Type: application/json
export async function DELETE(req: Request, ctx: { params: Promise<{ path?: string[] }> }) {
  const joined = await resolve(req, ctx);
  if (!joined) return Response.json({ message: "非法路径" }, { status: 400 });
  return relay(joined, "DELETE", req, await req.text());
}
export async function PUT(req: Request, ctx: { params: Promise<{ path?: string[] }> }) {
  const joined = await resolve(req, ctx);
  if (!joined) return Response.json({ message: "非法路径" }, { status: 400 });
  return relay(joined, "PUT", req, await req.text());
}
