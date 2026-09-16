import AnalyticsBoard from "@/components/analytics/AnalyticsBoard";
import { RequireAuth } from "@/components/auth/RequireAuth";

export default function AnalyticsPage() {
  return (
    <RequireAuth>
      <AnalyticsBoard />
    </RequireAuth>
  );
}
