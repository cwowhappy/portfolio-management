"use client";

import { RequireAdmin } from "@/components/auth/RequireAdmin";
import AdminBoard from "@/components/admin/AdminBoard";

export default function AdminPage() {
  return (
    <RequireAdmin>
      <AdminBoard />
    </RequireAdmin>
  );
}
