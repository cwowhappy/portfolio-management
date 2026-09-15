import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import QuestionnaireForm from "@/components/allocation/QuestionnaireForm";
import * as allocationApi from "@/lib/allocationApi";

vi.mock("@/lib/allocationApi", async () => {
  const actual = await vi.importActual<typeof import("@/lib/allocationApi")>("@/lib/allocationApi");
  return { ...actual, fetchQuestionnaire: vi.fn() };
});
const api = vi.mocked(allocationApi);

const oneQuestion = (id: string) => ({
  id, dimension: "承受能力·客观", text: `题目${id}`,
  options: [
    { id: "A", text: `选项${id}A` }, { id: "B", text: `选项${id}B` },
  ],
});

beforeEach(() => {
  vi.clearAllMocks();
  api.fetchQuestionnaire.mockResolvedValue({
    questions: [oneQuestion("Q1"), oneQuestion("Q2")],
  });
});
afterEach(cleanup);

describe("QuestionnaireForm", () => {
  it("加载题库渲染题目与单选项", async () => {
    render(<QuestionnaireForm onSubmit={vi.fn()} onCancel={vi.fn()} />);
    expect(await screen.findByText("题目Q1")).toBeTruthy();
    expect(screen.getByText("题目Q2")).toBeTruthy();
    expect(screen.getByText("选项Q1A")).toBeTruthy();
  });

  it("未答完禁提交；答完可提交且回调携带答卷", async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    render(<QuestionnaireForm onSubmit={onSubmit} onCancel={vi.fn()} />);
    const submit = await screen.findByRole("button", { name: "提交问卷" });
    expect((submit as HTMLButtonElement).disabled).toBe(true);

    fireEvent.click(screen.getByLabelText("选项Q1B"));
    fireEvent.click(screen.getByLabelText("选项Q2A"));
    expect((submit as HTMLButtonElement).disabled).toBe(false);

    fireEvent.click(submit);
    await waitFor(() => expect(onSubmit).toHaveBeenCalledWith([
      { questionId: "Q1", optionId: "B" },
      { questionId: "Q2", optionId: "A" },
    ]));
  });
});
