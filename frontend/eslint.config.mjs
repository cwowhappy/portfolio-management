import { FlatCompat } from "@eslint/eslintrc";
import reactHooks from "eslint-plugin-react-hooks";
import { dirname } from "node:path";
import { fileURLToPath } from "node:url";

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

const compat = new FlatCompat({
  baseDirectory: __dirname,
});

const eslintConfig = [
  ...compat.extends("next/core-web-vitals", "next/typescript"),
  {
    ignores: [
      ".next/**",
      "node_modules/**",
      "out/**",
      "coverage/**",
      "test-results/**",
      "playwright-report/**",
      "next-env.d.ts",
    ],
  },
  {
    rules: {
      // 规范 5.1：禁止显式 any，跨网络边界一律 zod 派生类型
      "@typescript-eslint/no-explicit-any": "error",
      // 下划线前缀参数视为有意未用（如 mock 函数占位签名）
      "@typescript-eslint/no-unused-vars": [
        "warn",
        { argsIgnorePattern: "^_", varsIgnorePattern: "^_" },
      ],
      // 规范 4.2/4.4：react-hooks recommended-latest（含 React Compiler 静态检查规则，
      // 只开 lint 规则，不启用 Compiler 编译）。next 配置经 FlatCompat 解析到的
      // eslint-plugin-react-hooks 即本包 v7，此处仅追加规则、不重复注册插件。
      ...reactHooks.configs["recommended-latest"].rules,
      // 规范 6.2：禁止 dangerouslySetInnerHTML
      "react/no-danger": "error",
      // 规范 6.2：Markdown 渲染禁止引入 rehype-raw（不允许渲染原始 HTML）
      "no-restricted-imports": [
        "error",
        {
          paths: [
            {
              name: "rehype-raw",
              message:
                "禁止 rehype-raw：Markdown 渲染不允许放行原始 HTML（规范 6.2）。",
            },
          ],
        },
      ],
    },
  },
  {
    // 规范 1.2：components/** 禁止直接 fetch，数据访问一律走 lib/
    // （测试文件集中在 frontend/tests/，天然不在此范围）
    files: ["components/**/*.{ts,tsx}"],
    rules: {
      "no-restricted-syntax": [
        "error",
        {
          selector: "CallExpression[callee.name='fetch']",
          message:
            "组件内禁止直接 fetch：数据访问必须经 lib/*Api.ts（规范 1.2）。",
        },
      ],
    },
  },
];

export default eslintConfig;
