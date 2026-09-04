import { afterEach, describe, it, expect, vi } from "vitest";
import { cleanup, render, screen, fireEvent } from "@testing-library/react";
import GroupManager from "@/components/portfolio/GroupManager";
import { addCashTransaction, createGroup, deleteGroup, renameGroup } from "@/lib/portfolioApi";

vi.mock("@/lib/portfolioApi", () => ({
  createGroup: vi.fn().mockResolvedValue({}),
  renameGroup: vi.fn().mockResolvedValue({}),
  deleteGroup: vi.fn().mockResolvedValue({}),
  addCashTransaction: vi.fn().mockResolvedValue({}),
}));

const groups = [
  { id: 1, name: "华泰", type: "ACCOUNT" as const, positionCount: 0, cashBalance: 0 },
  { id: 2, name: "观察", type: "TAG" as const, positionCount: 0, cashBalance: 0 },
];

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("GroupManager", () => {
  it("新建分组调用 createGroup", async () => {
    const onChanged = vi.fn();
    render(<GroupManager groups={[]} onChanged={onChanged} />);
    fireEvent.change(screen.getByPlaceholderText("分组名（如 华泰）"), { target: { value: "华泰" } });
    fireEvent.click(screen.getByRole("button", { name: "新建" }));
    await vi.waitFor(() => expect(onChanged).toHaveBeenCalled());
    expect(vi.mocked(createGroup)).toHaveBeenCalledWith({ name: "华泰", type: "ACCOUNT" });
  });

  it("改名分组调用 renameGroup", async () => {
    const onChanged = vi.fn();
    render(<GroupManager groups={groups} onChanged={onChanged} />);
    fireEvent.click(screen.getAllByRole("button", { name: "改名" })[0]);
    fireEvent.change(screen.getByLabelText("改名输入"), { target: { value: "东财" } });
    fireEvent.click(screen.getByRole("button", { name: "保存" }));
    await vi.waitFor(() => expect(onChanged).toHaveBeenCalled());
    expect(vi.mocked(renameGroup)).toHaveBeenCalledWith(1, { name: "东财" });
  });

  it("现金转入调用 addCashTransaction", async () => {
    const onChanged = vi.fn();
    render(<GroupManager groups={groups} onChanged={onChanged} />);
    fireEvent.change(screen.getByLabelText("金额"), { target: { value: "10000" } });
    fireEvent.click(screen.getByRole("button", { name: "录入" }));
    await vi.waitFor(() => expect(onChanged).toHaveBeenCalled());
    expect(vi.mocked(addCashTransaction)).toHaveBeenCalledWith(
      expect.objectContaining({ groupId: 1, type: "DEPOSIT", amount: 10000 }),
    );
  });

  it("现金转出调用 addCashTransaction(WITHDRAW)", async () => {
    const onChanged = vi.fn();
    render(<GroupManager groups={groups} onChanged={onChanged} />);
    fireEvent.change(screen.getByLabelText("转入转出"), { target: { value: "WITHDRAW" } });
    fireEvent.change(screen.getByLabelText("金额"), { target: { value: "5000" } });
    fireEvent.click(screen.getByRole("button", { name: "录入" }));
    await vi.waitFor(() => expect(onChanged).toHaveBeenCalled());
    expect(vi.mocked(addCashTransaction)).toHaveBeenCalledWith(
      expect.objectContaining({ groupId: 1, type: "WITHDRAW", amount: 5000 }),
    );
  });

  describe("删除分组", () => {
    it("确认后调用 deleteGroup 并刷新", async () => {
      vi.spyOn(window, "confirm").mockReturnValue(true);
      const onChanged = vi.fn();
      render(<GroupManager groups={groups} onChanged={onChanged} />);
      fireEvent.click(screen.getAllByRole("button", { name: /删除/ })[0]);
      await vi.waitFor(() => expect(onChanged).toHaveBeenCalled());
      expect(vi.mocked(deleteGroup)).toHaveBeenCalledWith(1);
      expect(window.confirm).toHaveBeenCalled();
    });

    it("取消二次确认时不调用 deleteGroup", async () => {
      vi.spyOn(window, "confirm").mockReturnValue(false);
      const onChanged = vi.fn();
      render(<GroupManager groups={groups} onChanged={onChanged} />);
      fireEvent.click(screen.getAllByRole("button", { name: /删除/ })[0]);
      expect(vi.mocked(deleteGroup)).not.toHaveBeenCalled();
      expect(onChanged).not.toHaveBeenCalled();
    });

    it("非空分组不显示删除按钮", () => {
      const nonEmpty = [
        { id: 1, name: "有持仓", type: "ACCOUNT" as const, positionCount: 3, cashBalance: 0 },
      ];
      render(<GroupManager groups={nonEmpty} onChanged={vi.fn()} />);
      expect(screen.queryByRole("button", { name: /删除/ })).toBeNull();
    });
  });
});
