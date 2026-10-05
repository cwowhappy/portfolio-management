import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/lib/skillApi", () => ({
  fetchSkills: vi.fn(),
  saveSkillConfig: vi.fn(),
}));

import SkillSettingsPage from "@/components/skill/SkillSettingsPage";
import { fetchSkills, saveSkillConfig, type SkillItem } from "@/lib/skillApi";

const skill: SkillItem = {
  skillCode: "tushare_data",
  description: "Tushare 数据技能",
  category: "data",
  defaultEnabled: true,
  dependsOnProvider: null,
  enabled: false,
};

function deferred<T>() {
  let resolve!: (v: T) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

describe("SkillSettingsPage", () => {
  beforeEach(() => {
    vi.mocked(fetchSkills).mockResolvedValue([skill]);
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it("连点技能开关仅触发一次保存（in-flight 防连点）", async () => {
    const d = deferred<SkillItem[]>();
    vi.mocked(saveSkillConfig).mockReturnValueOnce(d.promise);
    render(<SkillSettingsPage />);

    const box = await screen.findByRole("checkbox");
    fireEvent.click(box);
    fireEvent.click(box);

    expect(saveSkillConfig).toHaveBeenCalledTimes(1);

    d.resolve([{ ...skill, enabled: true }]);
    await waitFor(() =>
      expect(screen.getByText("已启用")).toBeTruthy(),
    );
  });

  it("saveSkillConfig 抛非 Error 值时错误条渲染兜底文案（非空、非 undefined）", async () => {
    vi.mocked(saveSkillConfig).mockRejectedValueOnce("boom");
    render(<SkillSettingsPage />);

    fireEvent.click(await screen.findByRole("checkbox"));

    const err = await screen.findByText("操作失败");
    expect(err.textContent).toBeTruthy();
    expect(err.textContent).not.toBe("undefined");
  });
});
