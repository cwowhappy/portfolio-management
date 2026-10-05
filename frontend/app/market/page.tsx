import { Suspense } from "react";
import MarketBoard from "@/components/market/MarketBoard";

// MarketBoard 内 useSearchParams 预渲染需 Suspense 边界（照 screener/intelligence 页先例）；
// fallback 形态照行情台既有加载态（指数条三卡 + 报价/K 线两块骨架）
export default function MarketPage() {
  return (
    <Suspense
      fallback={
        <div className="mx-auto max-w-[1240px] px-6 pb-16 pt-8" aria-label="加载中">
          <div className="mb-8 grid grid-cols-3 gap-3">
            <div className="skeleton h-[96px]" />
            <div className="skeleton h-[96px]" />
            <div className="skeleton h-[96px]" />
          </div>
          <div className="mb-6 skeleton h-[120px]" />
          <div className="skeleton h-[380px]" />
        </div>
      }
    >
      <MarketBoard />
    </Suspense>
  );
}
