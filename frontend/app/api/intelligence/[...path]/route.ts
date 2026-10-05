import { joinSegments, relay } from "@/lib/proxy";

// /api/intelligence/** 同源反代（照 allocation 先例）：工作台四区块只读（GET）、
// 绑定码生成（POST）、订阅保存（PUT）、解绑（DELETE），全部透传 Cookie 即可
// （生产 CSRF 已关，ADR-0007 同源会话前提，无需任何 token/header）。

export const dynamic = "force-dynamic";

// ctx.params 段已解码：经 joinSegments 逐段重编码（防 %2F/%3F 注入分隔符）并拒 `..`（防逃前缀）
async function resolve(req: Request, ctx: { params: Promise<{ path?: string[] }> }) {
  const { path = [] } = await ctx.params;
  const joined = joinSegments("/api/intelligence", path);
  return joined === null ? null : joined + new URL(req.url).search;
}

export async function GET(req: Request, ctx: { params: Promise<{ path?: string[] }> }) {
  const joined = await resolve(req, ctx);
  if (!joined) return Response.json({ message: "非法路径" }, { status: 400 });
  return relay(joined, "GET", req);
}
export async function POST(req: Request, ctx: { params: Promise<{ path?: string[] }> }) {
  const joined = await resolve(req, ctx);
  if (!joined) return Response.json({ message: "非法路径" }, { status: 400 });
  return relay(joined, "POST", req, await req.text());
}
export async function PUT(req: Request, ctx: { params: Promise<{ path?: string[] }> }) {
  const joined = await resolve(req, ctx);
  if (!joined) return Response.json({ message: "非法路径" }, { status: 400 });
  return relay(joined, "PUT", req, await req.text());
}
export async function DELETE(req: Request, ctx: { params: Promise<{ path?: string[] }> }) {
  const joined = await resolve(req, ctx);
  if (!joined) return Response.json({ message: "非法路径" }, { status: 400 });
  return relay(joined, "DELETE", req);
}
