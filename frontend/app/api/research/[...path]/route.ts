import { relay } from "@/lib/proxy";

// /api/research/** 同源反代（照 journal/wiki 先例）：PATCH 为本域特有方法
// （PATCH /projects/{id} 改标题/切阶段/手动标记），DELETE 后端无端点不导出。

export const dynamic = "force-dynamic";

async function resolve(req: Request, ctx: { params: Promise<{ path?: string[] }> }) {
  const { path = [] } = await ctx.params;
  const u = new URL(req.url);
  return "/api/research" + (path.length ? "/" + path.join("/") : "") + u.search;
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
export async function PATCH(req: Request, ctx: { params: Promise<{ path?: string[] }> }) {
  return relay(await resolve(req, ctx), "PATCH", req, await req.text());
}
