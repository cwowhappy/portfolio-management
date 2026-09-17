import IndustryDrilldown from "@/components/industry/IndustryDrilldown";

// Next 15：动态路由 server 组件的 params 是 Promise，必须 await（仓库首例）。
export default async function IndustryDrilldownPage(
  { params }: { params: Promise<{ industryCode: string }> },
) {
  const { industryCode } = await params;
  return <IndustryDrilldown industryCode={industryCode} />;
}
