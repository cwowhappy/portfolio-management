import IntelligenceSettingsPage from "@/components/intelligence/IntelligenceSettingsPage";
import { RequireAuth } from "@/components/auth/RequireAuth";

export default function Page() {
  return (
    <RequireAuth>
      <IntelligenceSettingsPage />
    </RequireAuth>
  );
}
