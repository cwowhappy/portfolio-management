import { relay } from "@/lib/proxy";

export const dynamic = "force-dynamic";

async function resolve(ctx: { params: Promise<{ path?: string[] }> }) {
  const { path = [] } = await ctx.params;
  return "/api/allocation" + (path.length ? "/" + path.join("/") : "");
}

export async function GET(req: Request, ctx: { params: Promise<{ path?: string[] }> }) {
  return relay(await resolve(ctx), "GET", req);
}
export async function POST(req: Request, ctx: { params: Promise<{ path?: string[] }> }) {
  return relay(await resolve(ctx), "POST", req, await req.text());
}
export async function PUT(req: Request, ctx: { params: Promise<{ path?: string[] }> }) {
  return relay(await resolve(ctx), "PUT", req, await req.text());
}
export async function DELETE(req: Request, ctx: { params: Promise<{ path?: string[] }> }) {
  return relay(await resolve(ctx), "DELETE", req);
}
