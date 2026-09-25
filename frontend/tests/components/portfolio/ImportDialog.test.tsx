import { afterEach, beforeEach, describe, it, expect, vi } from "vitest";
import { cleanup, render, screen, fireEvent, within } from "@testing-library/react";
import ImportDialog from "@/components/portfolio/ImportDialog";
import { importCsv } from "@/lib/portfolioImportApi";

vi.mock("@/lib/portfolioImportApi", () => ({
  importCsv: vi.fn(),
  templateHref: () => "/api/portfolio/import/template",
}));

const csv = new File(["date,type,code\n"], "import.csv", { type: "text/csv" });

function openDialog() {
  fireEvent.click(screen.getByRole("button", { name: "批量导入" }));
}

function pickFile() {
  fireEvent.change(screen.getByLabelText("CSV 文件"), { target: { files: [csv] } });
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(importCsv).mockResolvedValue({ importedCount: 0, rowErrors: [] });
});

afterEach(() => {
  cleanup();
});

describe("ImportDialog", () => {
  it("未选文件时提交按钮禁用，直接点击也不调 API", () => {
    render(<ImportDialog onImported={vi.fn()} />);
    openDialog();
    const submit = screen.getByRole("button", { name: "导入" });
    expect(submit).toHaveProperty("disabled", true);
    fireEvent.click(submit);
    expect(vi.mocked(importCsv)).not.toHaveBeenCalled();
  });

  it("全成功：显示成功导入 N 笔并触发 onImported（父层 reload）", async () => {
    const onImported = vi.fn();
    vi.mocked(importCsv).mockResolvedValueOnce({ importedCount: 6, rowErrors: [] });
    render(<ImportDialog onImported={onImported} />);
    openDialog();
    pickFile();
    fireEvent.click(screen.getByRole("button", { name: "导入" }));
    expect(await screen.findByText("成功导入 6 笔")).toBeTruthy();
    expect(vi.mocked(importCsv)).toHaveBeenCalledWith(csv);
    expect(onImported).toHaveBeenCalledTimes(1);
  });

  it("rowErrors 非空：渲染行号+原因错误表，不触发 onImported", async () => {
    const onImported = vi.fn();
    vi.mocked(importCsv).mockResolvedValueOnce({
      importedCount: 0,
      rowErrors: [
        { row: 3, reason: "证券代码不存在：999999" },
        { row: 5, reason: "数量必须为正整数" },
      ],
    });
    render(<ImportDialog onImported={onImported} />);
    openDialog();
    pickFile();
    fireEvent.click(screen.getByRole("button", { name: "导入" }));
    const table = await screen.findByTestId("import-row-errors");
    expect(within(table).getByText("3")).toBeTruthy();
    expect(within(table).getByText("证券代码不存在：999999")).toBeTruthy();
    expect(within(table).getByText("5")).toBeTruthy();
    expect(within(table).getByText("数量必须为正整数")).toBeTruthy();
    expect(screen.queryByText(/成功导入/)).toBeNull();
    expect(onImported).not.toHaveBeenCalled();
  });

  it("文件级错误（400）：行内显示 Error.message，不触发 onImported", async () => {
    const onImported = vi.fn();
    vi.mocked(importCsv).mockRejectedValueOnce(new Error("文件过大（上限 1MB）"));
    render(<ImportDialog onImported={onImported} />);
    openDialog();
    pickFile();
    fireEvent.click(screen.getByRole("button", { name: "导入" }));
    expect(await screen.findByText("文件过大（上限 1MB）")).toBeTruthy();
    expect(onImported).not.toHaveBeenCalled();
  });

  it("结果态可再导一次：回到表单态且文件清空、提交再次禁用", async () => {
    vi.mocked(importCsv).mockResolvedValue({ importedCount: 2, rowErrors: [] });
    render(<ImportDialog onImported={vi.fn()} />);
    openDialog();
    pickFile();
    fireEvent.click(screen.getByRole("button", { name: "导入" }));
    await screen.findByText("成功导入 2 笔");
    fireEvent.click(screen.getByRole("button", { name: "再导一次" }));
    expect(screen.getByRole("button", { name: "导入" })).toHaveProperty("disabled", true);
    expect(screen.queryByText("成功导入 2 笔")).toBeNull();
  });

  it("提交中显示导入中且禁用，重复点击不重复提交（useSaveAction 防连点）", async () => {
    vi.mocked(importCsv).mockImplementationOnce(() => new Promise(() => {}));
    render(<ImportDialog onImported={vi.fn()} />);
    openDialog();
    pickFile();
    fireEvent.click(screen.getByRole("button", { name: "导入" }));
    const busy = await screen.findByRole("button", { name: "导入中…" });
    expect(busy).toHaveProperty("disabled", true);
    fireEvent.click(busy);
    expect(vi.mocked(importCsv)).toHaveBeenCalledTimes(1);
  });

  it("模板下载链接指向模板端点", () => {
    render(<ImportDialog onImported={vi.fn()} />);
    openDialog();
    const link = screen.getByRole("link", { name: "下载模板" });
    expect(link.getAttribute("href")).toBe("/api/portfolio/import/template");
    expect(link).toHaveProperty("download", "");
  });

  it("取消按钮与遮罩点击关闭对话框，重开时状态已重置", () => {
    render(<ImportDialog onImported={vi.fn()} />);
    openDialog();
    fireEvent.click(screen.getByRole("button", { name: "取消" }));
    expect(screen.queryByTestId("import-dialog")).toBeNull();

    openDialog();
    expect(screen.getByRole("button", { name: "导入" })).toHaveProperty("disabled", true);
    // 遮罩空白处点击同样关闭
    fireEvent.click(screen.getByTestId("import-dialog"));
    expect(screen.queryByTestId("import-dialog")).toBeNull();
  });
});
