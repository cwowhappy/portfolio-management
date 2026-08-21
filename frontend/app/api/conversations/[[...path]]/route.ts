import { relay } from "@/lib/proxy";

export const dynamic = "force-dynamic";

export async function GET(req: Request, ctx: { params: Promise<{ path?: string[] }> }) {
  const { path = [] } = await ctx.params;
  return relay("/api/conversations" + (path.length ? "/" + path.join("/") : ""), "GET", req);
}
export async function POST(req: Request) {
  return relay("/api/conversations", "POST", req, await req.text());
}
export async function PUT(req: Request, ctx: { params: Promise<{ path?: string[] }> }) {
  const { path = [] } = await ctx.params;
  return relay("/api/conversations/" + path.join("/"), "PUT", req, await req.text());
}
export async function DELETE(req: Request, ctx: { params: Promise<{ path?: string[] }> }) {
  const { path = [] } = await ctx.params;
  return relay("/api/conversations/" + path.join("/"), "DELETE", req);
}
