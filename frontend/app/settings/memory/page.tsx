import { RequireAuth } from "@/components/auth/RequireAuth";
import MemorySettingsPage from "@/components/memory/MemorySettingsPage";

export default function Page() {
  return (
    <RequireAuth>
      <MemorySettingsPage />
    </RequireAuth>
  );
}
