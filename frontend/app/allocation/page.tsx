import AllocationBoard from "@/components/allocation/AllocationBoard";
import { RequireAuth } from "@/components/auth/RequireAuth";

export default function AllocationPage() {
  return (
    <RequireAuth>
      <AllocationBoard />
    </RequireAuth>
  );
}
