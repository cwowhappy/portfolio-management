import { RequireAuth } from "@/components/auth/RequireAuth";
import WikiBoard, { type WikiTab } from "@/components/wiki/WikiBoard";

const TAB_KEYS: readonly string[] = ["principle", "book", "concept", "research"];

export default async function WikiPage({ searchParams }: { searchParams: Promise<{ tab?: string }> }) {
  const { tab } = await searchParams; // Next 15：searchParams 是 Promise（同 params）
  const initialTab = TAB_KEYS.includes(tab ?? "") ? (tab as WikiTab) : "principle";
  return (
    <RequireAuth>
      <WikiBoard initialTab={initialTab} />
    </RequireAuth>
  );
}
