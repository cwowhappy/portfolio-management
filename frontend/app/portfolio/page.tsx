import PortfolioBoard from "@/components/portfolio/PortfolioBoard";
import { RequireAuth } from "@/components/auth/RequireAuth";

export default function PortfolioPage() {
  return (
    <RequireAuth>
      <PortfolioBoard />
    </RequireAuth>
  );
}
