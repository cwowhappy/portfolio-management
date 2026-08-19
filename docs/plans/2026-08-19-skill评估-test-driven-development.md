# test-driven-development skill 评估方案

> 评估对象：`.agents/skills/test-driven-development`
> 评估时间：2026-08-19
> 目的：验证 skill 的结构合规性、触发有效性、内容质量、技术准确性、可用性，并修复发现的问题。

## 一、评估维度与标准

### 1. 结构与格式

| 检查项 | 通过标准 |
|---|---|
| 目录结构 | 含 SKILL.md；可选 references/、assets/；无多余的 scripts/ |
| frontmatter | name + description 必填，无未允许字段 |
| 命名规范 | name 为 kebab-case，≤64 字符 |
| 无冗余文件 | 无 README / CHANGELOG 等辅助文档 |

### 2. 触发有效性

| 检查项 | 通过标准 |
|---|---|
| description 完整性 | 同时说明「做什么」与「何时用」，覆盖 TDD 相关触发词 |
| description 合规 | ≤1024 字符，无尖括号 |

### 3. 内容质量

| 检查项 | 通过标准 |
|---|---|
| SKILL.md 简洁性 | < 500 行、< 5k 词 |
| 渐进披露 | 语言/框架细节下沉 references，SKILL.md 一处链接 |
| 工作流完整性 | 覆盖「编写计划」+「执行计划」+ Red-Green-Refactor |
| TDD 方法论正确 | test list、Fake/Triangulate/Obvious、AAA、FIRST、测试行为而非实现 |
| 与现有 skill 去重 | 不与 writing-plans / executing-plans / test-runner 重复 |

### 4. 技术准确性（对照真实项目）

| 检查项 | 通过标准 |
|---|---|
| Java 参考 | 根包 `com.portfolio.invest`；包结构 web/agent/market/config；mock 风格；ArchUnit 规则；JaCoCo 80%；gradlew 命令 |
| TypeScript 参考 | Vitest3 + jsdom + v8 80%；RTL；mock 风格；npm 命令；依赖真实存在于 package.json |

### 5. 可用性实测

| 检查项 | 通过标准 |
|---|---|
| skill 加载 | 通过 quick_validate，被 harness 发现 |
| 参考文件可访问 | references/ 相对路径正确、可被读取 |
| 工作流可执行 | 参考中的命令/代码可在本项目真实运行 |

## 二、执行方法

1. **自动化校验**：quick_validate.py + 行数/字数统计 + 文件清单。
2. **事实核对**：对照 `backend/build.gradle`、`frontend/package.json`、`frontend/vitest.config.ts`、`docs/backend-package-conventions.md` 与真实测试文件（`PackageConventionsTest.java`、`MarketControllerTest.java`、`MarketDataServiceTest.java`、`MarketBoard.test.tsx`、`tests/setup.ts`）。
3. **修复**：对发现的不准确项直接修正 references，修正后重新校验。

## 三、评估结果

### 结论：通过（修复 6 处技术不准确项后）

### 逐项结果

| # | 检查项 | 结果 | 说明 |
|---|---|---|---|
| 1.1 | 目录结构 | ✅ | SKILL.md + references/ + assets/，无 scripts/ |
| 1.2 | frontmatter | ✅ | name + description，quick_validate 通过 |
| 1.3 | 命名规范 | ✅ | test-driven-development（kebab-case，24 字符） |
| 1.4 | 无冗余文件 | ✅ | 仅 4 个必要文件，无 README/CHANGELOG |
| 2.1 | description 完整性 | ✅ | 覆盖「做什么 + 何时用」，中英文触发词齐全 |
| 2.2 | description 合规 | ✅ | 未超 1024 字符，无尖括号 |
| 3.1 | SKILL.md 简洁性 | ✅ | 94 行 / 306 词 |
| 3.2 | 渐进披露 | ✅ | 框架细节下沉 references，SKILL.md 一处链接 |
| 3.3 | 工作流完整性 | ✅ | 编写计划 + 执行计划 + Red-Green-Refactor |
| 3.4 | TDD 方法论正确 | ✅ | test list / Fake-Triangulate-Obvious / AAA / FIRST |
| 3.5 | 与现有 skill 去重 | ✅ | 不重复 writing-plans / executing-plans / test-runner |
| 4.1 | Java 参考 | ⚠️→✅ | 初版包名/ArchUnit/mock 风格与真实项目不符，已修复 |
| 4.2 | TypeScript 参考 | ⚠️→✅ | 初版用未安装的 userEvent，已改 fireEvent 并补真实约定 |
| 5.1 | skill 加载 | ✅ | validator 通过，harness 已发现并纳入目录 |
| 5.2 | 参考文件可访问 | ✅ | references/、assets/ 相对路径正确 |
| 5.3 | 工作流可执行 | ✅ | 命令/代码与 build.gradle、package.json、真实测试一致 |

### 发现并修复的问题

1. **Java 根包错误**：`com.portfolio.market` → `com.portfolio.invest.market`（真实根包 `com.portfolio.invest`，子包 web/agent/market/config）。
2. **ArchUnit 规则失真**：初版用不存在的 `..domain..` 包与 `PackageConventionTest`；改为真实的 `PackageConventionsTest`（onlyAccess 白名单 + slices 无环 + ImportOption.DoNotIncludeTests）。
3. **Mock 风格不符**：初版用 @Mock/@InjectMocks 与 @WebMvcTest/@MockitoBean；本项目实际用纯 `mock()` + 构造注入，已改为真实风格并注明「不用 Spring 测试切片」。
4. **测试命名不符**：初版推荐 should<行为> + @DisplayName；本项目用中文描述性方法名（如 `search空关键词抛INVALID_QUERY`），已对齐。
5. **TypeScript 用未安装依赖**：初版示例用 @testing-library/user-event（package.json 无此依赖）；改为 fireEvent + waitFor。
6. **测试目录/别名偏差**：初版命令用 src/x.test.tsx；本项目测试在 frontend/tests/，@ 别名指向 frontend 根，已修正并补「本项目测试风格」章节。

### 遗留说明（非缺陷）

- references 里的 Red-Green 示例（PriceFormatter / formatPrice）为教学示意类，非项目现有类；包名与风格已与真实项目对齐，可直接套用该模式写新类。
