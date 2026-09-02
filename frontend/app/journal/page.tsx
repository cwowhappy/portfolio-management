import JournalBoard from "@/components/journal/JournalBoard";
import { RequireAuth } from "@/components/auth/RequireAuth";

export default function JournalPage() {
  return (
    <RequireAuth>
      <JournalBoard />
    </RequireAuth>
  );
}
