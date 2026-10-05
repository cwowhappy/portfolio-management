import { joinSegments, relay } from "@/lib/proxy";

export const dynamic = "force-dynamic";

// ctx.params 段已解码：经 joinSegments 逐段重编码（防 %2F/%3F 注入分隔符）并拒 `..`（防逃前缀）
async function resolve(req: Request, ctx: { params: Promise<{ path?: string[] }> }) {
  const { path = [] } = await ctx.params;
  const joined = joinSegments("/api/journal", path);
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
