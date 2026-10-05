import SkillSettingsPage from "@/components/skill/SkillSettingsPage";
import { RequireAuth } from "@/components/auth/RequireAuth";

export default function Page() {
  return (
    <RequireAuth>
      <SkillSettingsPage />
    </RequireAuth>
  );
}
