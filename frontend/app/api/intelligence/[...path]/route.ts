import { relay } from "@/lib/proxy";

// /api/intelligence/** 同源反代（照 allocation 先例）：工作台四区块只读（GET）、
// 绑定码生成（POST）、订阅保存（PUT）、解绑（DELETE），全部透传 Cookie 即可
// （生产 CSRF 已关，ADR-0007 同源会话前提，无需任何 token/header）。

export const dynamic = "force-dynamic";

async function resolve(req: Request, ctx: { params: Promise<{ path?: string[] }> }) {
  const { path = [] } = await ctx.params;
  const u = new URL(req.url);
  return "/api/intelligence" + (path.length ? "/" + path.join("/") : "") + u.search;
}

export async function GET(req: Request, ctx: { params: Promise<{ path?: string[] }> }) {
  return relay(await resolve(req, ctx), "GET", req);
}
export async function POST(req: Request, ctx: { params: Promise<{ path?: string[] }> }) {
  return relay(await resolve(req, ctx), "POST", req, await req.text());
}
export async function PUT(req: Request, ctx: { params: Promise<{ path?: string[] }> }) {
  return relay(await resolve(req, ctx), "PUT", req, await req.text());
}
export async function DELETE(req: Request, ctx: { params: Promise<{ path?: string[] }> }) {
  return relay(await resolve(req, ctx), "DELETE", req);
}
