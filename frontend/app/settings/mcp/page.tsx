import McpSettingsPage from "@/components/mcp/McpSettingsPage";
import { RequireAuth } from "@/components/auth/RequireAuth";

export default function Page() {
  return (
    <RequireAuth>
      <McpSettingsPage />
    </RequireAuth>
  );
}
