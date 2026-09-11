// 体积断言入口（非业务代码）：把 chart 管线的全部运行时依赖钉进一个 bundle。
import { echarts } from "../lib/echarts-setup";
import * as builders from "../components/charts/optionBuilders";
// 引用防树摇：esbuild 对未被使用的 import 会保留模块副作用，此处显式引用双保险
if (typeof echarts.use !== "function" || Object.keys(builders).length < 4) {
  throw new Error("chart-bundle-entry 引用不完整");
}
