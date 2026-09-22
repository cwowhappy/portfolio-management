import type { WikiEntryType, WikiEntryView } from "@/lib/types";

export interface NotePanelProps {
  type: WikiEntryType;
  entries: WikiEntryView[];
  onChanged: () => void;
}

/** Task 4 占位组件：让 WikiBoard 编译通过，Task 5/6 实现真体。 */
export default function NotePanel(_props: NotePanelProps) {
  return null;
}
