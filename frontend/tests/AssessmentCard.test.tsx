import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import AssessmentCard from "@/components/allocation/AssessmentCard";
import * as allocationApi from "@/lib/allocationApi";
import type { AssessmentView } from "@/lib/types";

vi.mock("@/lib/allocationApi", async () => {
  const actual = await vi.importActual<typeof import("@/lib/allocationApi")>("@/lib/allocationApi");
  return { ...actual, createPlan: vi.fn(), submitAssessment: vi.fn(), fetchQuestionnaire: vi.fn() };
});
const api = vi.mocked(allocationApi);

const growth: AssessmentView = {
  totalScore: 35, profile: "GROWTH", profileName: "成长",
  weights: [{ assetClass: "STOCK", weight: 65 }, { assetClass: "BOND", weight: 20 }],
  answers: { Q1: "A" }, assessedAt: "2026-09-15T00:00:00Z",
};

const twoQuestions = {
  questions: [
    { id: "Q1", dimension: "承受能力·客观", text: "题目Q1",
      options: [{ id: "A", text: "选项Q1A" }, { id: "B", text: "选项Q1B" }] },
    { id: "Q2", dimension: "承受能力·客观", text: "题目Q2",
      options: [{ id: "A", text: "选项Q2A" }, { id: "B", text: "选项Q2B" }] },
  ],
};

beforeEach(() => { vi.clearAllMocks(); });
afterEach(cleanup);

describe("AssessmentCard", () => {
  it("无结果：显示开始测评", () => {
    render(<AssessmentCard assessment={null} onChanged={vi.fn()} />);
    expect(screen.getByRole("button", { name: "开始测评" })).toBeTruthy();
  });

  it("有结果：显示档位总分与推荐权重", () => {
    render(<AssessmentCard assessment={growth} onChanged={vi.fn()} />);
    expect(screen.getByTestId("assessment-profile").textContent).toContain("成长");
    expect(screen.getByText(/35/)).toBeTruthy();
    expect(screen.getByText(/65/)).toBeTruthy();
  });

  it("按推荐创建方案：source=ASSESSMENT、权重与推荐一致，成功后刷新", async () => {
    const onChanged = vi.fn();
    api.createPlan.mockResolvedValue({ id: 9, name: "测评推荐·成长", source: "ASSESSMENT", weights: [], active: false, rebalanceFrequency: "OFF", lastRebalancedAt: null });
    render(<AssessmentCard assessment={growth} onChanged={onChanged} />);

    fireEvent.click(screen.getByRole("button", { name: "按推荐创建方案" }));
    await waitFor(() => expect(api.createPlan).toHaveBeenCalledWith({
      name: "测评推荐·成长", source: "ASSESSMENT",
      weights: growth.weights,
    }));
    expect(onChanged).toHaveBeenCalled();
  });

  it("提交成功后收起问卷并刷新", async () => {
    api.fetchQuestionnaire.mockResolvedValue(twoQuestions);
    const onChanged = vi.fn();
    api.submitAssessment.mockResolvedValue(growth);
    render(<AssessmentCard assessment={null} onChanged={onChanged} />);

    fireEvent.click(screen.getByRole("button", { name: "开始测评" }));
    expect(await screen.findByTestId("questionnaire-form")).toBeTruthy();

    fireEvent.click(screen.getByLabelText("选项Q1B"));
    fireEvent.click(screen.getByLabelText("选项Q2A"));
    fireEvent.click(screen.getByRole("button", { name: "提交问卷" }));
    await waitFor(() => expect(api.submitAssessment).toHaveBeenCalledWith([
      { questionId: "Q1", optionId: "B" },
      { questionId: "Q2", optionId: "A" },
    ]));
    await waitFor(() => expect(onChanged).toHaveBeenCalled());
    expect(screen.queryByTestId("questionnaire-form")).toBeNull(); // 收起
  });
});
