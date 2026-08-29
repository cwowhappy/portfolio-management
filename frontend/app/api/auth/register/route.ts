// 同源反代：/api/auth/register → 后端 /api/auth/register（透传 Cookie/Set-Cookie）。
import type { NextRequest } from "next/server";
import { relay } from "@/lib/proxy";

export const dynamic = "force-dynamic";

export async function POST(req: NextRequest) {
  return relay("/api/auth/register", "POST", req, await req.text());
}
