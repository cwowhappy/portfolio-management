import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import NewProjectForm from "@/components/research/NewProjectForm";
import * as researchApi from "@/lib/researchApi";
import type { ProjectView } from "@/lib/researchSchemas";

// 立项表单（F1 自 page 下沉组件层）：searchParams 预填/缺省标题推导/前端校验/withTemplate——
// 原 page 壳逻辑只能被 e2e 间接覆盖，此处按 vitest 组件层直测。

const { pushMock } = vi.hoisted(() => ({ pushMock: vi.fn() }));

vi.mock("next/navigation", () => ({
  useSearchParams: () => new URLSearchParams("code=600519&name=贵州茅台&industry=BK0477"),
  useRouter: () => ({ push: pushMock }),
}));

vi.mock("@/lib/researchApi", async () => {
  const actual = await vi.importActual<typeof import("@/lib/researchApi")>("@/lib/researchApi");
  return {
    ...actual,
    createProject: vi.fn(),
  };
});

const api = vi.mocked(researchApi);

const created: ProjectView = {
  id: 9,
  stockCode: "600519",
  stockName: "贵州茅台",
  industryCode: "BK0477",
  title: "贵州茅台研究",
  currentStage: "NEW_ANALYSIS",
  status: "ACTIVE",
  intelligenceAlertEnabled: true,
  createdAt: "2026-10-12T08:00:00Z",
  updatedAt: "2026-10-12T08:00:00Z",
};

async function renderForm() {
  render(<NewProjectForm />);
  await screen.findByTestId("research-new");
}

describe("NewProjectForm（立项表单，F1 下沉）", () => {
  beforeEach(() => {
    api.createProject.mockReset().mockResolvedValue(created);
    pushMock.mockReset();
  });

  afterEach(cleanup);

  it("searchParams 预填标的字段；缺省标题提交为「<标的名称>研究」并跳详情页", async () => {
    await renderForm();
    expect((screen.getByLabelText("标的代码") as HTMLInputElement).value).toBe("600519");
    expect((screen.getByLabelText("标的名称") as HTMLInputElement).value).toBe("贵州茅台");
    expect((screen.getByLabelText("行业代码") as HTMLInputElement).value).toBe("BK0477");
    expect((screen.getByLabelText("项目标题") as HTMLInputElement).value).toBe("");
    fireEvent.click(screen.getByRole("button", { name: "立项" }));
    await waitFor(() =>
      expect(api.createProject).toHaveBeenCalledWith({
        stockCode: "600519",
        stockName: "贵州茅台",
        industryCode: "BK0477",
        title: "贵州茅台研究",
        withTemplate: true,
      }),
    );
    await waitFor(() => expect(pushMock).toHaveBeenCalledWith("/research/9"));
  });

  it("缺标的代码：前端校验拦截（行内错误，不发起创建）", async () => {
    await renderForm();
    fireEvent.change(screen.getByLabelText("标的代码"), { target: { value: " " } });
    fireEvent.click(screen.getByRole("button", { name: "立项" }));
    await screen.findByText("标的代码不能为空");
    expect(api.createProject).not.toHaveBeenCalled();
  });

  it("取消勾选 SOP 模板后以 withTemplate=false 提交", async () => {
    await renderForm();
    fireEvent.click(screen.getByLabelText("带入新分析 SOP 模板"));
    expect((screen.getByLabelText("带入新分析 SOP 模板") as HTMLInputElement).checked).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: "立项" }));
    await waitFor(() =>
      expect(api.createProject).toHaveBeenCalledWith(
        expect.objectContaining({ title: "贵州茅台研究", withTemplate: false }),
      ),
    );
  });
});
