import { joinSegments, relay } from "@/lib/proxy";

export const dynamic = "force-dynamic";

// 透传入站 query string（对齐 journal/admin 的 relay 语义），避免上游拼出丢参地址
const withSearch = (req: Request, base: string) => base + new URL(req.url).search;

// ctx.params 段已解码：经 joinSegments 逐段重编码（防 %2F/%3F 注入分隔符）并拒 `..`（防逃前缀）
function resolve(req: Request, path: string[]) {
  const joined = joinSegments("/api/conversations", path);
  return joined === null ? null : withSearch(req, joined);
}

export async function GET(req: Request, ctx: { params: Promise<{ path?: string[] }> }) {
  const { path = [] } = await ctx.params;
  const joined = resolve(req, path);
  if (!joined) return Response.json({ message: "非法路径" }, { status: 400 });
  return relay(joined, "GET", req);
}
export async function POST(req: Request) {
  return relay(withSearch(req, "/api/conversations"), "POST", req, await req.text());
}
export async function PUT(req: Request, ctx: { params: Promise<{ path?: string[] }> }) {
  const { path = [] } = await ctx.params;
  const joined = resolve(req, path);
  if (!joined) return Response.json({ message: "非法路径" }, { status: 400 });
  return relay(joined, "PUT", req, await req.text());
}
export async function DELETE(req: Request, ctx: { params: Promise<{ path?: string[] }> }) {
  const { path = [] } = await ctx.params;
  const joined = resolve(req, path);
  if (!joined) return Response.json({ message: "非法路径" }, { status: 400 });
  return relay(joined, "DELETE", req);
}
