import { relay } from "@/lib/proxy";

export const dynamic = "force-dynamic";

// 透传入站 query string（对齐 journal/admin 的 relay 语义），避免上游拼出丢参地址
const withSearch = (req: Request, base: string) => base + new URL(req.url).search;

export async function GET(req: Request, ctx: { params: Promise<{ path?: string[] }> }) {
  const { path = [] } = await ctx.params;
  return relay(
    withSearch(req, "/api/conversations" + (path.length ? "/" + path.join("/") : "")),
    "GET",
    req,
  );
}
export async function POST(req: Request) {
  return relay(withSearch(req, "/api/conversations"), "POST", req, await req.text());
}
export async function PUT(req: Request, ctx: { params: Promise<{ path?: string[] }> }) {
  const { path = [] } = await ctx.params;
  return relay(
    withSearch(req, "/api/conversations/" + path.join("/")),
    "PUT",
    req,
    await req.text(),
  );
}
export async function DELETE(req: Request, ctx: { params: Promise<{ path?: string[] }> }) {
  const { path = [] } = await ctx.params;
  return relay(
    withSearch(req, "/api/conversations/" + path.join("/")),
    "DELETE",
    req,
  );
}
