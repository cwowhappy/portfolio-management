import { Suspense } from "react";
import ScreenerBoard from "@/components/screening/ScreenerBoard";

export default function ScreenerPage() {
  return (
    <Suspense fallback={<div className="mx-auto max-w-6xl p-8 skeleton h-40 rounded-2xl" />}>
      <ScreenerBoard />
    </Suspense>
  );
}
