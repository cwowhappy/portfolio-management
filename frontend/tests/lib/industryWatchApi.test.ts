import { afterEach, describe, expect, it, vi } from "vitest";
import { fetchIndustryWatch, unwatchIndustry, watchIndustry } from "@/lib/industryWatchApi";

vi.stubGlobal("fetch", vi.fn());
const fetchMock = vi.mocked(fetch);

afterEach(() => vi.clearAllMocks());

const ROW = { industryCode: "801780", addedAt: "2026-09-25T00:00:00Z" };

describe("industryWatchApi", () => {
  it("fetchIndustryWatch 走反代 GET /api/industry-watch 并 zod 校验", async () => {
    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify([ROW]), { status: 200 }));
    const rows = await fetchIndustryWatch();
    expect(fetchMock).toHaveBeenCalledWith("/api/industry-watch", expect.anything());
    expect(rows[0].industryCode).toBe("801780");
    expect(rows[0].addedAt).toBe("2026-09-25T00:00:00Z");
  });

  it("watchIndustry POST JSON body；unwatchIndustry DELETE 路径参数（204 幂等无返回值）", async () => {
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 })); // 后端幂等关注返回 204 无体
    await watchIndustry("801780");
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/industry-watch");
    expect(init.method).toBe("POST");
    expect(JSON.parse(String(init.body))).toEqual({ industryCode: "801780" });

    fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }));
    await unwatchIndustry("801780");
    expect(fetchMock.mock.lastCall?.[0]).toBe("/api/industry-watch/801780");
  });

  it("匿名 401 抛后端 message（需登录端点）", async () => {
    fetchMock.mockResolvedValueOnce(
      new Response(JSON.stringify({ message: "未认证" }), { status: 401 }),
    );
    await expect(fetchIndustryWatch()).rejects.toThrow("未认证");
  });
});
