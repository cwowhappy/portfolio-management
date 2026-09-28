import { cleanup, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import StageProgress from "@/components/research/StageProgress";
import type { ResearchStage, StageCompletion } from "@/lib/researchSchemas";

// StageProgress 真实渲染断 DOM（不 mock 依赖）：完成度读模型由后端
// StageCompletionService 唯一计算（S6/NFR-1，前端不重复实现），组件只忠实呈现
// 三态 + 完成方式角标（D22：不做百分比）。

afterEach(cleanup);

const c = (stage: ResearchStage, status: StageCompletion["status"], basis: StageCompletion["basis"]): StageCompletion => ({
  stage,
  status,
  basis,
});

const completions: Record<ResearchStage, StageCompletion> = {
  NEW_ANALYSIS: c("NEW_ANALYSIS", "COMPLETED", "AUTO"),
  STRATEGY: c("STRATEGY", "IN_PROGRESS", "PENDING"),
  POSITION: c("POSITION", "NOT_STARTED", "PENDING"),
  REVIEW: c("REVIEW", "COMPLETED", "MANUAL"),
};

function stageItem(stage: ResearchStage): HTMLElement {
  return screen.getByTestId("stage-progress").querySelector(`li[data-stage="${stage}"]`) as HTMLElement;
}

describe("StageProgress（三态进度 + 角标，D22）", () => {
  it("四阶段按声明序渲染，三态文案 + 完成角标（自动/手动）", () => {
    render(<StageProgress completions={completions} />);
    const items = screen.getByTestId("stage-progress").querySelectorAll("li");
    expect(items).toHaveLength(4);
    // 声明序与后端 ResearchStage 枚举一致
    expect(items[0].getAttribute("data-stage")).toBe("NEW_ANALYSIS");
    expect(items[1].getAttribute("data-stage")).toBe("STRATEGY");
    expect(items[2].getAttribute("data-stage")).toBe("POSITION");
    expect(items[3].getAttribute("data-stage")).toBe("REVIEW");

    // 新分析：完成 + 自动角标（产物齐套）
    expect(within(stageItem("NEW_ANALYSIS")).getByText("新分析")).toBeTruthy();
    expect(within(stageItem("NEW_ANALYSIS")).getByText("完成")).toBeTruthy();
    expect(within(stageItem("NEW_ANALYSIS")).getByText("自动")).toBeTruthy();
    // 策略：进行中、无角标
    expect(within(stageItem("STRATEGY")).getByText("制定投资策略")).toBeTruthy();
    expect(within(stageItem("STRATEGY")).getByText("进行中")).toBeTruthy();
    // 建仓与持仓：未开始
    expect(within(stageItem("POSITION")).getByText("未开始")).toBeTruthy();
    // 复盘：完成 + 手动角标（D16 兜底）
    expect(within(stageItem("REVIEW")).getByText("完成")).toBeTruthy();
    expect(within(stageItem("REVIEW")).getByText("手动")).toBeTruthy();
  });

  it("未完成阶段（PENDING）不带完成角标", () => {
    render(<StageProgress completions={completions} />);
    const strategy = stageItem("STRATEGY");
    expect(within(strategy).queryByText("自动")).toBeNull();
    expect(within(strategy).queryByText("手动")).toBeNull();
  });

  it("REOPENED 覆盖场景：读模型压回「进行中」，即便产物齐套也不显示完成/角标", () => {
    // 后端语义：manual REOPENED > manual COMPLETED > AUTO——重开阶段即使产物齐套，
    // 读模型产出恒为 { IN_PROGRESS, PENDING }，组件呈现为进行中且无角标
    const reopened: Record<ResearchStage, StageCompletion> = {
      ...completions,
      NEW_ANALYSIS: c("NEW_ANALYSIS", "IN_PROGRESS", "PENDING"),
    };
    render(<StageProgress completions={reopened} />);
    const analysis = stageItem("NEW_ANALYSIS");
    expect(within(analysis).getByText("进行中")).toBeTruthy();
    expect(within(analysis).queryByText("完成")).toBeNull();
    expect(within(analysis).queryByText("自动")).toBeNull();
  });

  it("不渲染任何百分比（D22 三态不做进度百分比）", () => {
    const { container } = render(<StageProgress completions={completions} />);
    expect(container.textContent).not.toContain("%");
    expect(container.textContent).not.toContain("％");
  });

  it("缺键阶段按「未开始」兜底渲染，不崩溃", () => {
    // zod record 不强制枚举键齐全（服务端 EnumMap 恒给四键），组件对缺键容错
    const partial = {
      NEW_ANALYSIS: completions.NEW_ANALYSIS,
      STRATEGY: completions.STRATEGY,
    } as Record<ResearchStage, StageCompletion>;
    render(<StageProgress completions={partial} />);
    expect(within(stageItem("POSITION")).getByText("未开始")).toBeTruthy();
    expect(within(stageItem("REVIEW")).getByText("未开始")).toBeTruthy();
  });
});
