# TypeScript 前端 TDD 参考（本项目：Next.js 15 + React 19 + Vitest 3 + RTL）

## 工具链

| 层 | 工具 | 说明 |
|---|---|---|
| 测试框架 | Vitest 3 | globals 模式，describe/it/expect |
| 断言 | expect + @testing-library/jest-dom | toBeInTheDocument 等 |
| DOM 测试 | @testing-library/react + jsdom | render/screen/事件 |
| Mock | vi.fn / vi.mock / vi.spyOn | 函数、模块、fetch |
| 覆盖率 | @vitest/coverage-v8 | npm test 自动带覆盖率 |

> 本项目用 Vitest；API 与 Jest 几乎一致（vi ↔ jest）。若目标项目是 Jest，把 vi. 换成 jest. 即可，差异见文末。

## 本项目测试风格（先读这个，保持一致）

- **测试文件**放在 `frontend/tests/` 下（非与源码同目录），配置见 `vitest.config.ts`。
- **别名**：`@` 指向 frontend 根，import 用 `@/components/...` 或 `@/lib/...`。
- **交互模拟**：用 `fireEvent`（change/click）+ `waitFor` 断言异步；项目未安装 `@testing-library/user-event`，勿用。
- **mock**：`vi.mock("@/lib/api", () => ({ ... }))` 整体替换模块，再用 `vi.mocked(fn)` 拿带类型断言。
- **命名**：describe 分组 + 中文 it("...") 描述行为；一个 it 只测一个行为。

## 运行命令

```bash
npm test                  # vitest run --coverage（单次 + 覆盖率）
npm run test:watch        # vitest 监听模式（TDD 首选）
npx vitest run tests/x.test.tsx      # 单文件
npx vitest run -t "returns total"    # 按测试名过滤
```

> RED 阶段用监听模式（test:watch）改测试立刻看红/绿；GREEN 后跑一次 npm test 确认覆盖率与无回归。

## Red-Green-Refactor 示例（纯函数）

目标行为：formatPrice(1500.5) 返回 "1500.50"。

**RED** —— 先写失败测试：

```ts
import { describe, it, expect } from "vitest";
import { formatPrice } from "./format";

describe("formatPrice", () => {
  it("把价格格式化为两位小数", () => {
    expect(formatPrice(1500.5)).toBe("1500.50");
  });
});
```

运行确认 **红**：formatPrice 未定义（引用错误）—— 正是 RED 想要的失败。

**GREEN** —— 最小实现：

```ts
export function formatPrice(value: number): string {
  return value.toFixed(2);
}
```

**REFACTOR / 下一步** —— 用 Triangulation 逼出边界：负数、NaN、浮点精度（如 0.1 + 0.2），让实现更健壮。

## 测试命名约定

- describe 分组 = 被测单元（组件名/模块名/函数名）
- it("应<行为> when <条件>") 写人话，让失败信息自解释
- 一个 it 只断言一个行为；边界用 it.each 参数化

## Arrange-Act-Assert

```ts
it("把价格格式化为两位小数", () => {
  // Arrange
  const input = 1500.5;
  // Act
  const result = formatPrice(input);
  // Assert
  expect(result).toBe("1500.50");
});
```

## React 组件测试（Testing Library）

```tsx
import { render, screen, fireEvent } from "@testing-library/react";
import { describe, it, expect, vi } from "vitest";
import { SearchBox } from "./SearchBox";

describe("SearchBox", () => {
  it("提交时回调输入的关键词", () => {
    const onSearch = vi.fn();
    render(<SearchBox onSearch={onSearch} />);

    fireEvent.change(screen.getByRole("textbox"), { target: { value: "茅台" } });
    fireEvent.click(screen.getByRole("button", { name: "搜索" }));

    expect(onSearch).toHaveBeenCalledWith("茅台");
  });
});
```

查询优先级：getByRole 最优先（可访问性），其次 getByLabelText/getByPlaceholderText/getByText，最后才用 getByTestId。异步出现用 findBy* / waitFor；断言不存在用 queryBy*。

## Mock

```ts
// 单函数 mock
const onSearch = vi.fn();
onSearch.mockResolvedValue({ ok: true });

// mock 模块
vi.mock("./api", () => ({
  fetchQuote: vi.fn().mockResolvedValue({ price: 1500 }),
}));

// spy 已有对象方法
vi.spyOn(console, "error").mockImplementation(() => {});
```

> 测试行为而非实现：优先 mock 边界（网络/时间/随机），少 mock 内部细节。

## 覆盖率门槛

- npm test 带 @vitest/coverage-v8，报告在 coverage/index.html
- 本项目门槛：语句/分支 ≥ 80%（见 README），低于会失败
- 分支不足 = 边界用例没写，回测试清单补

## 常见反模式（避免）

- 直接测内部状态/实现细节 → 测用户可观察的行为
- 过度 mock 被测对象本身 → 只在边界 mock
- 测试间共享可变数据 → 每个测试自给自足（beforeEach 清理）
- 把快照当免费断言 → 快照变化需人肉 review，易被盲目接受

## Jest 差异备忘

若目标项目用 Jest（非 Vitest）：vi. → jest.；配置 ts-jest / babel-jest；监听用 npm test -- --watch。其余 describe/it/expect 与 Testing Library 用法一致。
