import { relay } from "@/lib/proxy";

export const dynamic = "force-dynamic";

async function resolve(req: Request, ctx: { params: Promise<{ path?: string[] }> }) {
  const { path = [] } = await ctx.params;
  const u = new URL(req.url);
  return "/api/industry-curation" + (path.length ? "/" + path.join("/") : "") + u.search;
}

export async function GET(req: Request, ctx: { params: Promise<{ path?: string[] }> }) {
  return relay(await resolve(req, ctx), "GET", req);
}
export async function POST(req: Request, ctx: { params: Promise<{ path?: string[] }> }) {
  const path = await resolve(req, ctx);
  // multipart（CSV 批量导入）：字节流原样透传 + 显式带 inbound Content-Type（含 boundary，不能重编）
  const contentType = req.headers.get("content-type");
  if (contentType?.startsWith("multipart/form-data")) {
    return relay(path, "POST", req, await req.arrayBuffer(), undefined, contentType);
  }
  return relay(path, "POST", req, await req.text());
}
export async function PUT(req: Request, ctx: { params: Promise<{ path?: string[] }> }) {
  return relay(await resolve(req, ctx), "PUT", req, await req.text());
}
export async function DELETE(req: Request, ctx: { params: Promise<{ path?: string[] }> }) {
  return relay(await resolve(req, ctx), "DELETE", req);
}
