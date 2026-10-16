# testcontainers-python × ryuk × Colima 兼容性调研

> 调研日期：2026-10-16。调研方式：全部结论以一手来源（GitHub 源码按 tag 逐版本核对、GitHub Releases/Issues/PR、官方文档）为准，逐条给出永久链接；本机（Colima + macOS arm64 + Docker server 29.5.2）行为以受控探针复核。
>
> 背景输入（此前本机实证，下文称「用户探针」）：TC-py 4.15.0 默认钉 ryuk 0.8.1 时客户端被 SIGKILL 后收割随机；钉 0.13.0 时 5/5 确定性收割；钉 0.14.0 时 ryuk 日志 `prune check clients=0`、`removed containers=0`，60s connection_timeout 后空剪退出，客户端连接从未抵达。本次调研对上述三点做了同机复核（下文称「复核探针」），见 [§7](#7-对本机实证的机制再解释)。
>
> 关联文档：[03-后端测试架构.md](03-后端测试架构.md)「Ryuk 收割」节；本仓库注入点见 `collector/tests/conftest.py:21-34`。

---

## 0. 结论速览

**TL;DR**

1. TC-py 至今（4.15.0，最新发布）默认钉 **ryuk 0.8.1**（2024-07 的镜像）。4.x 历史上只换过三次默认：0.5.1（4.1.0 引入 ryuk）→ 0.7.0（4.3.0）→ 0.8.1（4.8.0）。
2. TC-py 的 Reaper 从第一版起就是 **start 时立即主动 TCP 连接 ryuk:8080**（eager connect），不是 HTTP API，协议是裸 TCP + 文本行 `label=org.testcontainers.session-id=<uuid>\r\n`，ryuk 逐行回 `ACK\n`。**4.15.0 只发送、从不读 ACK**——连接成功 ≠ 过滤器注册成功，这是「收割随机」的文献级根因（[issue #1114](https://github.com/testcontainers/testcontainers-python/issues/1114)、修复 [PR #1124](https://github.com/testcontainers/testcontainers-python/pulls/1124)（未合并））。
3. TC-py 等待 ryuk 就绪的正则是 `.* Started!`，而 ryuk 0.10.1+ 改日志为 `msg=Started`（无感叹号）。**该等待自 4.13.0 起是死代码**（`waiting_for(...)` 写在 `.start()` 之后，永不执行），所以 4.13+ 配任何 ryuk 都不会被日志正则卡死；但 4.10~4.12 是活性等待，**钉 ryuk ≥0.10.0 必定 20s 超时失败**（社区实证 [#744](https://github.com/testcontainers/testcontainers-python/issues/744)）。
4. 「0.8.1 给自己也打 session-id 标签、先删到自己即漏」的假设**不成立**（源码+`docker inspect` 双重反证）：TC-py 自 4.3.2 起 ryuk 容器**不打** session-id 标签；ryuk 镜像自带 `LABEL org.testcontainers.ryuk=true`，prune 时显式跳过自己。0.8.1 随机收割的更可能机制是 §2 所述 connect-vs-bind 竞态。
5. Colima 因素只有一个硬约束：**宿主侧 `~/.colima/*/docker.sock` 是转发 socket 文件，永远挂不进 VM**（moby 对 bind 源路径先 `mkdir -p`，virtiofs 下报 `operation not supported`），必须用 `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` 指向 VM 内路径。这与本仓库 conftest 注入一致，也是 testcontainers 官方 issue 的标准答案。
6. ryuk 0.13.0 与 0.14.0 的 **Go 源码完全相同**（diff 只有 Dockerfile：0.14.0 加了 UPX 压缩）。复核探针里 0.14.0 连接与 SIGKILL 收割均成功，用户的「0.14.0 不可用」未能复现，疑为 §7 所述竞态的偶发命中，非版本不兼容。

**兼容矩阵**（收割可靠性判定；【实测】= 本机实证，【文献】= 源码/issue 推断）

| TC-py 版本 | ryuk 版本 | Reaper 等待形态 | SIGKILL 后收割判定 |
|---|---|---|---|
| ≤ 3.7.1 | （无 ryuk） | — | 【文献】无 reaper，泄漏由 `__exit__` 兜底，进程被杀即漏 |
| 4.1.0~4.2.0 | 0.5.1（默认） | 活性等待 `Started!`（无超时）| 【文献】协议匹配可收；进程被杀依赖 ryuk 侧断连触发 |
| 4.3.0~4.3.2 | 0.7.0（默认） | 同上 + 注入 `RYUK_RECONNECTION_TIMEOUT` | 【文献】可收 |
| 4.4.0~4.7.x | 0.7.0（默认） | 活性等待 + connect 重试 50×0.5s（#543） | 【文献】可收，Colima 端口延迟缓解 |
| 4.8.0~4.8.2 | 0.8.1（默认） | 活性等待 + connect 重试 | 【文献】可收；本会话用户探针观察随机，机制见 §7 |
| 4.9.0~4.9.2 | 0.8.1（默认） | + socket 1s 超时、日志等待 20s 超时、host/port 缺失即抛（#742） | 【文献】同上 |
| 4.10.0~4.12.x | 0.8.1（默认） | 同上 | 【文献】默认组合可收；connect 竞态（#1114）仍是失效模式。**钉 ryuk ≥0.10.0 不可用**（等待 `Started!` 20s 超时，#744） |
| 4.13.0~4.15.0 | 0.8.1（默认） | **死代码**（waiting_for 在 start 后，#838 起） | 【文献】不受日志正则约束；用户探针：收割随机【实测·用户】 |
| 4.15.0 | 钉 0.13.0 | 死代码 | **确定性收割**：用户探针 5/5【实测·用户】；复核探针 SIGKILL 收割成功【实测·复核】 |
| 4.15.0 | 钉 0.14.0 | 死代码 | 用户探针：连接未抵达、空剪【实测·用户】；**复核探针：连接成功、SIGKILL 收割成功，未能复现失败**【实测·复核】→ 疑为竞态偶发，非版本不兼容 |
| 4.13.0~4.15.0 | 钉 0.10.0 | 死代码 | 【文献】镜像自身缺陷（0.10.0 无 Started 日志，[#744](https://github.com/testcontainers/testcontainers-python/issues/744)） |
| （前瞻，未合并） | [PR #1131](https://github.com/testcontainers/testcontainers-python/pulls/1131) 拟把默认升 0.14.0 | [PR #1124](https://github.com/testcontainers/testcontainers-python/pulls/1124) 拟改为等 `Started` + 等 `ACK` 重试 | 【文献】合并前官方默认仍 0.8.1 |

---

## 1. TC-py 各版本钉的 ryuk 镜像默认值

默认值定义处（当前）：`RYUK_CONTAINER_IMAGE` 环境变量，fallback `testcontainers/ryuk:0.8.1` —
[src/testcontainers/core/config.py#L105 @ v4.15.0](https://github.com/testcontainers/testcontainers-python/blob/04267b35bdcf79ee9d2efbfdd61b2512dab0bdac/src/testcontainers/core/config.py#L105)

按 tag 逐版本核对该字符串（`git show <tag>:<path> | grep 'testcontainers/ryuk:'`），ryuk 集成始于 4.x：

| TC-py 版本 | 默认 ryuk | 变更提交（首次出现的 release） |
|---|---|---|
| ≤ 3.7.1 | 无 ryuk（v3.7.1 全树无 ryuk 引用） | — |
| 4.1.0、4.2.0 | `testcontainers/ryuk:0.5.1` | [d019874](https://github.com/testcontainers/testcontainers-python/commit/d019874)（PR [#314](https://github.com/testcontainers/testcontainers-python/pull/314)，2024-03-19） |
| 4.3.0~4.7.2 | `testcontainers/ryuk:0.7.0` | [472b2c2](https://github.com/testcontainers/testcontainers-python/commit/472b2c2)（PR [#509](https://github.com/testcontainers/testcontainers-python/pull/509)，2024-03-31，同时加 `RYUK_RECONNECTION_TIMEOUT`） |
| 4.8.0~**4.15.0（最新）** | `testcontainers/ryuk:0.8.1` | [b13b43d](https://github.com/testcontainers/testcontainers-python/commit/b13b43d)（PR [#650](https://github.com/testcontainers/testcontainers-python/pull/650)，2024-07-31） |

**最新发布版本**：TC-py **4.15.0**（2026-07-24，[GitHub Releases](https://github.com/testcontainers/testcontainers-python/releases/tag/testcontainers-v4.15.0)）；ryuk 镜像最新 **0.14.0**（2025-09-26，[Releases](https://github.com/testcontainers/moby-ryuk/releases/tag/0.14.0)）。注意默认值 0.8.1 落后镜像最新两个大版本。

## 2. TC-py 的 Reaper 实现细节（4.15.0 为准）

代码：[`src/testcontainers/core/container.py` 的 `Reaper` 类 @ v4.15.0](https://github.com/testcontainers/testcontainers-python/blob/04267b35bdcf79ee9d2efbfdd61b2512dab0bdac/src/testcontainers/core/container.py#L460)。

**连接时机：eager，在业务容器 start 时。** `DockerContainer.start()` 开头（非 ryuk 镜像且未禁用时）调用 `Reaper.get_instance()`（[L245-247](https://github.com/testcontainers/testcontainers-python/blob/04267b35bdcf79ee9d2efbfdd61b2512dab0bdac/src/testcontainers/core/container.py#L245)）；`_create_instance()` 启动 ryuk 容器后**立即**发起重连接（[L507-537](https://github.com/testcontainers/testcontainers-python/blob/04267b35bdcf79ee9d2efbfdd61b2512dab0bdac/src/testcontainers/core/container.py#L507)），不存在 lazy/首次使用才连的形态。全程裸 TCP socket，**不是 HTTP API**（历史也无 HTTP 版本；#838 只是把日志等待换成 WaitStrategy 类，协议从未变过）。

**连接失败是否阻断容器启动：是。** 两处会中断 `start()`：
- host/port 取不到 → 直接抛 `ContainerConnectException`，**无重试**（[L510-515](https://github.com/testcontainers/testcontainers-python/blob/04267b35bdcf79ee9d2efbfdd61b2512dab0bdac/src/testcontainers/core/container.py#L510)，#742 引入）。`get_exposed_port` → `DockerClient.port()` 在端口映射缺失时抛 `ConnectionError`（[docker_client.py#L366-373](https://github.com/testcontainers/testcontainers-python/blob/04267b35bdcf79ee9d2efbfdd61b2512dab0bdac/src/testcontainers/core/docker_client.py#L366)）——Colima 端口映射延迟（[colima #1424](https://github.com/abiosoft/colima/issues/1424)）正打在这里，且这一步**在重试循环之前**；
- TCP connect 重试 50 次 ×（1s 超时 + 0.5s 间隔，约 75s 预算）仍失败 → 抛最后一个异常（[L517-537](https://github.com/testcontainers/testcontainers-python/blob/04267b35bdcf79ee9d2efbfdd61b2512dab0bdac/src/testcontainers/core/container.py#L517)）。

**给 ryuk 容器和业务容器各打什么 label**：统一走 `create_labels(image, labels)`（[docker_client.py#L266](https://github.com/testcontainers/testcontainers-python/blob/04267b35bdcf79ee9d2efbfdd61b2512dab0bdac/src/testcontainers/core/docker_client.py#L266)、[labels.py#L16-35](https://github.com/testcontainers/testcontainers-python/blob/04267b35bdcf79ee9d2efbfdd61b2512dab0bdac/src/testcontainers/core/labels.py#L16)）：
- 业务容器：`org.testcontainers=true`、`org.testcontainers.lang=python`、`org.testcontainers.version=<tc 版本>`、**`org.testcontainers.session-id=<uuid>`**；
- **ryuk 容器自己：不带 session-id**——`if image == c.ryuk_image: return`（[labels.py#L31](https://github.com/testcontainers/testcontainers-python/blob/04267b35bdcf79ee9d2efbfdd61b2512dab0bdac/src/testcontainers/core/labels.py#L31)）。该豁免自 [3be6da3](https://github.com/testcontainers/testcontainers-python/commit/3be6da3)（#532，首发 4.3.2）起生效；4.3.1 及更早 ryuk 容器会被打上 session-id。
- ryuk 容器的自保护标签 `org.testcontainers.ryuk=true` **不是 TC-py 打的**，是镜像 Dockerfile 自带（见 §3）。复核探针 `docker inspect` 实测 ryuk 容器 labels：`{"org.testcontainers":"true","org.testcontainers.lang":"python","org.testcontainers.ryuk":"true","org.testcontainers.version":"4.15.0"}`，确无 session-id。【实测·复核】

**连接后的注册动作（关键缺陷）**：发送 `label=org.testcontainers.session-id=<uuid>\r\n` 后**立即认为成功**，不读 ryuk 的 `ACK` 回包（[L541](https://github.com/testcontainers/testcontainers-python/blob/04267b35bdcf79ee9d2efbfdd61b2512dab0bdac/src/testcontainers/core/container.py#L541)）。docker-proxy（Colima 默认 userland-proxy 开启时同样）会在 ryuk 进程 bind 之前就在发布端口 accept，于是「connect 成功」可能只是连到了 proxy，过滤器行随连接被 reset 丢失——TC-py 全程无感知，ryuk 侧则永远 `clients=0`。这是 [issue #1114](https://github.com/testcontainers/testcontainers-python/issues/1114)（2026-08-31）记录的「Reaper 报成功但过滤器从未到达 ryuk（静默泄漏）」，与用户探针 0.8.1 随机、0.14.0 `clients=0` 空剪的症状族完全一致。

**等待就绪逻辑的演变（为什么日志正则如今不构成约束）**：

| 版本区间 | 等待形态 | 出处 |
|---|---|---|
| 4.1.0~4.9.2 | `wait_for_logs(Reaper._container, r".* Started!")` 活性等待、无超时 | [d019874](https://github.com/testcontainers/testcontainers-python/commit/d019874) |
| 4.9.0~4.12.x | 活性等待 + `timeout=20, raise_on_exit=True`；socket `settimeout(1)` | [4ced198](https://github.com/testcontainers/testcontainers-python/commit/4ced198)（PR [#742](https://github.com/testcontainers/testcontainers-python/pull/742)，修 [#741](https://github.com/testcontainers/testcontainers-python/issues/741) 挂死） |
| **4.13.0~4.15.0** | `rc.waiting_for(LogMessageWaitStrategy(r".* Started!"))` 写在 `.start()` **之后** → builder setter 落在已启动容器上，**永不执行，死代码** | [003046b](https://github.com/testcontainers/testcontainers-python/commit/003046b)（PR [#838](https://github.com/testcontainers/testcontainers-python/pull/838)，首发 4.13.0；@ v4.15.0 即 [container.py#L505](https://github.com/testcontainers/testcontainers-python/blob/04267b35bdcf79ee9d2efbfdd61b2512dab0bdac/src/testcontainers/core/container.py#L505)） |

（#838 前 connect 重试的引入点：[90bb780](https://github.com/testcontainers/testcontainers-python/commit/90bb780)，PR [#543](https://github.com/testcontainers/testcontainers-python/pull/543)，修 Colima 端口绑定延迟 [#486](https://github.com/testcontainers/testcontainers-python/issues/486)，首发 4.4.0。）

**推断**：TC-py 4.13+ 下「钉新 ryuk 被日志正则卡死」的历史坑已因死代码而消失，约束只剩 TCP 连接与过滤器注册两条；而后者恰恰是没有确认机制的那条。

## 3. ryuk 镜像版本变更

**源码仓库**：`testcontainers/ryuk` 镜像由 [testcontainers/moby-ryuk](https://github.com/testcontainers/moby-ryuk) 构建（Go 单二进制；linux/windows 双 Dockerfile）。发布节奏：[0.8.1](https://github.com/testcontainers/moby-ryuk/releases/tag/0.8.1) 2024-07-15 → [0.9.0](https://github.com/testcontainers/moby-ryuk/releases/tag/0.9.0) 2024-08-12 → 0.10.x 2024-10 → [0.11.0](https://github.com/testcontainers/moby-ryuk/releases/tag/0.11.0) 2024-10-23 → [0.12.0](https://github.com/testcontainers/moby-ryuk/releases/tag/0.12.0) 2025-06-02 → [0.13.0](https://github.com/testcontainers/moby-ryuk/releases/tag/0.13.0) 2025-09-01 → [0.14.0](https://github.com/testcontainers/moby-ryuk/releases/tag/0.14.0) 2025-09-26。

### 0.8.1（TC-py 当前默认）的行为基线

单文件 `main.go`（[0.8.1 main.go](https://github.com/testcontainers/moby-ryuk/blob/2035aab9f891dc8617b8458c2322f43caeeb88d2/main.go)）：
- 监听 `net.Listen("tcp", ":8080")` 全接口；启动日志 `Started!`（[L163](https://github.com/testcontainers/moby-ryuk/blob/2035aab9f891dc8617b8458c2322f43caeeb88d2/main.go#L163)）——与 TC-py 等待正则 `.* Started!` 匹配。
- 客户端事件日志：`New client connected: <addr>`（[L228](https://github.com/testcontainers/moby-ryuk/blob/2035aab9f891dc8617b8458c2322f43caeeb88d2/main.go#L228)）、`Client disconnected`（L236）；用户探针在 0.8.1 下能看到这两条，说明 0.8.1 时 TCP 层连接与断连都正常送达。
- prune 触发：`connection_timeout`（默认 60s，[L55](https://github.com/testcontainers/moby-ryuk/blob/2035aab9f891dc8617b8458c2322f43caeeb88d2/main.go#L55)）内无任何客户端连接 → `Timeout waiting for connection`（L245）→ prune；全部客户端断开后等 `reconnection_timeout`（默认 10s）→ prune。
- **自排除**：prune 枚举匹配过滤器的容器时，跳过 `org.testcontainers.ryuk == "true"` 的容器（[L280-282](https://github.com/testcontainers/moby-ryuk/blob/2035aab9f891dc8617b8458c2322f43caeeb88d2/main.go#L280)；常量定义 L31）；该标签由镜像 Dockerfile 烧入（[linux/Dockerfile#L29 @ 0.8.1](https://github.com/testcontainers/moby-ryuk/blob/2035aab9f891dc8617b8458c2322f43caeeb88d2/linux/Dockerfile#L29)）。
- prune 动作：按过滤器 `ContainerRemove(Force+Volumes)` / `NetworksPrune` / `VolumesPrune` / 镜像 `ImagesPrune`——最后这个是 [PR #1131](https://github.com/testcontainers/testcontainers-python/pulls/1131) 要换默认 ryuk 的直接原因（见 §4）。

### 0.9.0 → 0.14.0 的主要变更

| ryuk 版本 | 关键变更 | 对 TC-py 客户端的影响 |
|---|---|---|
| 0.9.0 | 无行为变更（依赖/CI） | 无 |
| **0.10.0** | **Go 重写**：结构化 slog 日志（[PR #141](https://github.com/testcontainers/moby-ryuk/pull/141) shutdown race resilience，release notes）；镜像逐个 `ImageRemove`，不再调 `ImagesPrune` | 破坏性：0.10.0 只打 `listening` 日志，连 `Started` 都没有（[0.10.0 reaper.go#L143](https://github.com/testcontainers/moby-ryuk/blob/a1f6d78aa8a14bfabccbf6bd46ac67a207b9547b/reaper.go#L143)），TC-py 等待正则失配（[#744](https://github.com/testcontainers/testcontainers-python/issues/744)） |
| 0.10.1 | `fix: use Started instead of listening`（[91122e2](https://github.com/testcontainers/moby-ryuk/commit/91122e26462b04f5c29fc397740f41ed5de3c6c5)，PR [#167](https://github.com/testcontainers/moby-ryuk/pull/167)） | 日志变 `msg=Started`（**无感叹号**），TC-py ≤4.12 的 `.* Started!` 正则仍失配 → 活性等待 20s 超时抛 `TimeoutError` |
| 0.10.2 | `fix: slow shutdown after signal`（PR [#169](https://github.com/testcontainers/moby-ryuk/pull/169)） | 无 |
| 0.11.0 | `fix: multiple filters`（PR [#173](https://github.com/testcontainers/moby-ryuk/pull/173)） | 无 |
| 0.12.0 | 发布到 ghcr.io（PR [#200](https://github.com/testcontainers/moby-ryuk/pull/200)） | 无 |
| 0.13.0 | 换 `scratch` 基础镜像（静态二进制，PR [#211](https://github.com/testcontainers/moby-ryuk/pull/211)） | 启动更快（无动态链接/无 shell），下游 bind 更早 |
| **0.14.0** | **Go 代码与 0.13.0 零差异**（`git diff 0.13.0 0.14.0` 仅 Dockerfile）；新增 UPX 压缩（PR [#212](https://github.com/testcontainers/moby-ryuk/pull/212)） | ppc64le 上 UPX 启动失败（[moby-ryuk #231](https://github.com/testcontainers/moby-ryuk/issues/231)）；linux/arm64/amd64 无影响 |

### 0.14.0（=0.13.0 代码）与 0.8.1 的行为差异

新实现：[0.14.0 reaper.go](https://github.com/testcontainers/moby-ryuk/blob/b3726afd6cc2c36628abcc08e9cabac43f587384/reaper.go)、[config.go](https://github.com/testcontainers/moby-ryuk/blob/b3726afd6cc2c36628abcc08e9cabac43f587384/config.go)：

- **协议完全兼容**：仍是裸 TCP + 文本行 + `ACK\n`（[ackResponse 定义 L39](https://github.com/testcontainers/moby-ryuk/blob/b3726afd6cc2c36628abcc08e9cabac43f587384/reaper.go#L39)）；无握手、无 TLS、无认证。监听仍是 `:%d` 全接口（[L146](https://github.com/testcontainers/moby-ryuk/blob/b3726afd6cc2c36628abcc08e9cabac43f587384/reaper.go#L146)；复核探针实测日志 `msg=Started address=[::]:8080`，双栈通配）。**因此「bind 接口/协议/认证导致 TC-py 连不上 0.14.0」的假设都不成立。**
- 日志措辞：`Started`（L153）、`client connected`（L317）、`prune check` + `clients=N`（[L354](https://github.com/testcontainers/moby-ryuk/blob/b3726afd6cc2c36628abcc08e9cabac43f587384/reaper.go#L354)）、收尾 `removed containers=N ...`（[L700](https://github.com/testcontainers/moby-ryuk/blob/b3726afd6cc2c36628abcc08e9cabac43f587384/reaper.go#L700)）——与用户探针看到的 `prune check clients=0`、`removed containers=0` 逐字吻合。
- `connection_timeout` 语义保留：ticker 每 60s 检查，`clients==0` 时到期即空剪退出；首个客户端连上即停表；全断开则重置为 `reconnection_timeout`（10s）。`RYUK_RECONNECTION_TIMEOUT` 环境变量名两代一致（[config.go#L14-44](https://github.com/testcontainers/moby-ryuk/blob/b3726afd6cc2c36628abcc08e9cabac43f587384/config.go#L14)，TC-py 注入 [container.py#L500](https://github.com/testcontainers/testcontainers-python/blob/04267b35bdcf79ee9d2efbfdd61b2512dab0bdac/src/testcontainers/core/container.py#L500) 仍有效）。
- **prune 语义增强**：新增「变更检测」——只移除 `created <= now-1s`（`RYUK_RETRY_OFFSET=-1s`）的资源，检测到并发变更则按 `RYUK_CHANGES_RETRY_INTERVAL=1s` 重试（`resources()`/`affectedContainers()`，自排除仍是 [L456](https://github.com/testcontainers/moby-ryuk/blob/b3726afd6cc2c36628abcc08e9cabac43f587384/reaper.go#L456) 的 `org.testcontainers.ryuk` 标签）；新增 SIG 信号后 `RYUK_SHUTDOWN_TIMEOUT=10m` 的宽限剪除。
- 复核探针实测 0.14.0 时间线：start 后 0.5s `client connected` → `adding filter label=org.testcontainers.session-id=<uuid>` → SIGKILL 客户端 → `client disconnected clients=0` → 10s 后 prune → alpine 与 ryuk 容器均消失。【实测·复核】

**「为什么 TC-py 4.15 的连接可能到不了 0.14.0」——结论：不是 0.14.0 拒绝连接，而是连接根本没有形成或没有注册。** 候选机制按证据强度排序：
1. **docker-proxy accept 竞态**（#1114）：proxy 先于 ryuk bind 接受 TCP → TC-py connect 成功即收手 → 过滤器行丢失 → ryuk 永远 `clients=0`，60s 空剪。症状与用户探针逐字吻合（`prune check clients=0`）。
2. **`get_exposed_port` 瞬时失败无重试**（§2）：Colima 端口映射延迟（[colima #1424](https://github.com/abiosoft/colima/issues/1424)）使 `docker port` 查询瞬时为空 → `ConnectionError` → `start()` 直接中断 → ryuk 容器已起但无人连接 → 同样 `clients=0` 空剪。
3. 0.14.0 首次拉取镜像（UPX 后 ~2MB）造成的额外时延只是放大了窗口，不改变机制。

## 4. TC-py 官方对 ryuk 版本兼容的声明

**没有正式兼容矩阵文档**。可引用的官方/半官方表态按时间序：

- ryuk 升默认仅两次、且都是 chore 级：[#509](https://github.com/testcontainers/testcontainers-python/pull/509)（0.5.1→0.7.0，附启用 ryuk 测试套件）、[#650](https://github.com/testcontainers/testcontainers-python/pull/650)（0.7.0→0.8.1，无说明正文）。**官方从未声明「TC-py X 起需要 ryuk ≥ Y」**。
- [#744](https://github.com/testcontainers/testcontainers-python/issues/744)（2024-11，open）：官方 repo 内对「钉 ryuk 0.10.0 失败」的实证记录；社区结论指向镜像缺陷（配合 moby-ryuk [#177](https://github.com/testcontainers/moby-ryuk/issues/177)）与镜像预拉取/`DOCKER_HOST` 注入问题，官方未出矩阵。
- [PR #1124](https://github.com/testcontainers/testcontainers-python/pulls/1124)（2026-10，open，修 [#1114](https://github.com/testcontainers/testcontainers-python/issues/1114)）：**唯一成文的兼容性表述**——「`waiting_for` 在 `.start()` 之后所以等待从未运行；正则改为 `\bStarted\b`，因为 ryuk 0.8 打 `Started!` 而 0.11+ 打 `msg=Started`，旧正则在新镜像上会超时；connect 不等于注册，改为 connect+send+读 `ACK` 一体并重试」。
- [PR #1131](https://github.com/testcontainers/testcontainers-python/pulls/1131)（2026-10，open）：拟把默认升到 **0.14.0**，理由与 ryuk 版本强相关——「ryuk 0.8.1 用按 session 标签过滤的 `ImagesPrune`，Docker 29 默认 containerd 镜像存储下会破坏同 daemon 上其他进程的进行中拉取（moby/moby[#53321](https://github.com/moby/moby/issues/53321)）；ryuk 0.10.0+ 逐镜像 `ImageRemove`，不再调 `ImagesPrune`」。PR 正文同时承认「On `main` the startup wait doesn't run yet」（官方确认死代码现状）。
- 结论：在 #1124/#1131 合并之前，**「TC-py 4.13+ + 自钉 ryuk 0.13.0」是官方未背书但机制上安全**的组合（死等待不受正则约束、协议两代一致、自排除标签镜像自带）；这正是本仓库 conftest 的选择。

## 5. Colima 因子

**socket 转发架构**：Colima 把 VM（lima）内 `/var/run/docker.sock` 通过宿主侧 `~/.colima/<instance>/docker.sock` 转发文件暴露给 macOS 客户端（`docker context ls` 的 colima endpoint 即该路径；本机 `DOCKER_HOST=unix:///Users/<user>/.colima/default/docker.sock`）。VM 内才是真实 socket；宿主侧文件只是转发端点。colima 维护者 abiosoft 的原话（[#997 评论](https://github.com/abiosoft/colima/issues/997)）：「You should mount `/var/run/docker.sock` instead, the path to the docker socket **in the VM**, not on your macOS host.」

**`mkdir ... operation not supported` 的机制**：dockerd 对 bind-mount 源路径总是先 `mkdir -p`（moby `volume/mounts/mounts.go` 的既有行为，[#997 引 moby 源码 L209](https://github.com/abiosoft/colima/issues/997)）。当源路径是宿主侧转发 socket（如 `/Users/<user>/.colima/default/docker.sock`）时，该路径在 VM 内的 macOS 共享挂载（virtiofs/sshfs/9p）视图中不存在，daemon 尝试创建：
- **virtiofs**（vz 虚拟机引擎的默认挂载型，[#997](https://github.com/abiosoft/colima/issues/997)：「Start Colima using virtiofs (default when using `vz` over `qemu`)」）不支持该创建 → 返回 `Operation not supported`（而非常规的 `File exists`）→ 报错 `error while creating mount source path ... mkdir ...: operation not supported`；
- qemu + 9p/sshfs 组合下表现不同（可建目录或报 File exists），历史上故多见于 vz/virtiofs 报告。
- **复核探针实测复现**：`docker run -v /Users/<user>/.colima/default/docker.sock:/var/run/docker.sock ...` → `mkdir /Users/<user>/.colima/default/docker.sock: operation not supported`（本机 Colima vz/virtiofs，Docker server 29.5.2）。【实测·复核】→ 推论：**把宿主侧 Colima socket 挂进容器（即 ryuk 的 socket 挂载）在 Colima 上恒错**，唯一正确值是 VM 内路径 `/var/run/docker.sock`。这与用户探针「宿主侧路径对 ryuk 恒错」的结论一致。

**官方/社区标准配置**：
- testcontainers 侧：`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` 是第一优先级 socket 来源，「Used by Ryuk, Docker Compose, ...」（TC-py 官方文档 [Configuration — Docker Socket Path Detection](https://github.com/testcontainers/testcontainers-python/blob/04267b35bdcf79ee9d2efbfdd61b2512dab0bdac/docs/features/configuration.md)）；TC-Java 维护者 kiview 在 Colima 指南 issue 中确认「`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` is the value that will be used for bind mounting the Docker socket into the Ryuk container」（[testcontainers-java #5034](https://github.com/testcontainers/testcontainers-java/issues/5034)，其中给出 Colima 工作配置）。
- 逃生口：切 `colima start -t vz --mount-type sshfs`（或 9p）可绕过 virtiofs 限制（[#997 评论区](https://github.com/abiosoft/colima/issues/997)），但对 testcontainers 场景不如直接改 override 精准。
- `TESTCONTAINERS_RYUK_DISABLED=true` 是最后手段，维护者明确反对（关闭可靠清理，[testcontainers-java #5034 评论](https://github.com/testcontainers/testcontainers-java/issues/5034)）；本仓库已从禁用态切回启用态（`collector/tests/conftest.py`），方向与官方建议一致。

**Colima 版本/VM 型行为变更**（与本题相关者）：
- virtiofs 成为 vz 引擎默认挂载型（macOS 13+，`colima start -t vz`）后 `mkdir ... operation not supported` 报告集中出现（[#997](https://github.com/abiosoft/colima/issues/997)，2024-03，colima 0.6.8，closed-as-answered：改挂 VM 内路径）。
- 端口映射延迟（影响 ryuk connect 时序，见 §2 机制 2）：[colima #1424](https://github.com/abiosoft/colima/issues/1424)「Port mappings are exposed with a delay to the host」（closed）与 [#71](https://github.com/abiosoft/colima/issues/71)「Connection refused right after container start」（closed）。
- 未见官方对 ryuk 收割路径（socket → prune）的专门变更说明；`RYUK_*` 环境变量与 ryuk 侧行为和 Colima 版本无耦合。

## 6. 社区同类案例

| 案例 | 症状 | 与本案例的关系 |
|---|---|---|
| [testcontainers-python #1114](https://github.com/testcontainers/testcontainers-python/issues/1114)（2026-08-31，open） | Reaper 报成功但 session 过滤器从未到达 ryuk；测试进程被 kill 后整会话容器存活（**静默泄漏**）；docker-proxy 先 accept 是根因之一 | **症状族完全相同**（「客户端连接从未抵达 ryuk」的文献对应物）；修复即 [PR #1124](https://github.com/testcontainers/testcontainers-python/pulls/1124) |
| [testcontainers-python #486](https://github.com/testcontainers/testcontainers-python/issues/486)（open，colima, timing） | Colima 下 ryuk 启动时端口绑定偶发失败（IPv4/IPv6 端口映射时序） | Colima 端口时序的第一手案例；TC-py 侧补丁 [#543](https://github.com/testcontainers/testcontainers-python/pull/543)（4.4.0 起 connect 重试） |
| [testcontainers-python #741](https://github.com/testcontainers/testcontainers-python/issues/741) → [#742](https://github.com/testcontainers/testcontainers-python/pull/742) | ryuk 连接挂死（坏 docker host 时 50×长超时） | 1s socket 超时 + 20s 日志超时的由来（4.9.0 起） |
| [testcontainers-python #744](https://github.com/testcontainers/testcontainers-python/issues/744) + [moby-ryuk #177](https://github.com/testcontainers/moby-ryuk/issues/177) | 钉 ryuk 0.10.0 后容器无法启动（Apple M1 实证） | 「TC-py ≤4.12 活性等待 × 新 ryuk 日志措辞」不兼容的社区实证（§2/§4） |
| [moby-ryuk #231](https://github.com/testcontainers/moby-ryuk/issues/231)（open） | ryuk 0.14.0 在 ppc64le 启动失败（UPX） | 0.14.0 唯一的架构级回退案例；linux/arm64 不受影响 |
| [testcontainers-go #2074](https://github.com/testcontainers/testcontainers-go/issues/2074)（closed） | ryuk 0.6.0 启动即退（macOS + colima） | Colima × ryuk 启动类问题的 Go 侧同类案例 |
| [testcontainers-go #2596](https://github.com/testcontainers/testcontainers-go/issues/2596) / PR [#2508](https://github.com/testcontainers/testcontainers-go/pull/2508) | ryuk 容器在起好前被误删 / 查找 reaper 容器的竞态 | 「ryuk 生命周期竞态」家族，与 #1114 同类 |
| [testcontainers-python #983](https://github.com/testcontainers/testcontainers-python/issues/983) | Docker 29.3 下 dind 中 ryuk 难复现异常 | Docker 29 × ryuk 的新发问题域（与 #1131 的 containerd 动机同域） |
| [testcontainers-java #5034](https://github.com/testcontainers/testcontainers-java/issues/5034)（closed） | Colima 作 Docker 引擎时 ryuk 起不来 → `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` 解决 | socket override 标准答案的出处（§5） |

未检索到「colima + ryuk 收割随机 + python」三要素齐备的既有 issue；#1114（linux docker-proxy）是机制上最接近的既有报告。若需向上游确认，建议把本调研 §7 的机制分析补进 #1114 或 #486。

## 7. 对本机实证的机制再解释

对照文献逐条复核用户探针的三个观察（复核探针环境：同机 Colima vz、Docker server 29.5.2 linux/arm64、TC-py 4.15.0（venv 内与 main 逐字节一致）、alpine:3.20 业务容器、`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`）：

1. **「0.8.1 收割随机（一胜一负）」**——「给自己也打 session-id 标签、先删到自己即漏」假设被双重反证：TC-pY 不给 ryuk 容器打 session-id（[labels.py#L31](https://github.com/testcontainers/testcontainers-python/blob/04267b35bdcf79ee9d2efbfdd61b2512dab0bdac/src/testcontainers/core/labels.py#L31)，4.3.2 起）；镜像自带 `org.testcontainers.ryuk=true` 且 prune 显式跳过（[0.8.1 main.go#L280-282](https://github.com/testcontainers/moby-ryuk/blob/2035aab9f891dc8617b8458c2322f43caeeb88d2/main.go#L280)）。更一致的机制是 **#1114 连接注册竞态**：过滤器行送达则该轮收割成功，被 proxy 吞掉则该轮全漏——恰好表现为一胜一负。0.8.1（alpine 基底、动态链接）进程启动慢、bind 晚，竞态窗口比 scratch 基底的 0.13.0 宽，这可以解释「0.13.0 钉死后 5/5 确定收割」（窗口收窄为推测，非文献结论）。
2. **「钉 0.13.0 → 5/5 确定性收割」**——复核探针同样复现成功，且机制上 4.15.0 死等待使任何 ryuk ≥0.13 都可配（§2）；0.13.0=0.14.0 代码，若 0.13.0 稳定则差异不在镜像代码。
3. **「钉 0.14.0 → 连接从未抵达、`prune check clients=0` 空剪」**——**复核探针未能复现**：0.14.0 下 TC-py 正常连接（`client connected` + `adding filter`），SIGKILL 后 10s 内确定性收割 alpine，ryuk 自删。【实测·复核】日志措辞逐字对上了 0.14.0 源码，说明当时 0.14.0 容器确实健康运行了整 60s 而无人连接——最符合的机制是 §3 列出的 1/2 两条竞态在那一轮命中（连接被 proxy 吞或 `get_exposed_port` 瞬时失败中断了 Reaper 流程），而非 0.14.0 的版本性缺陷。**当前证据下，钉 0.13.0 或 0.14.0 都是可用的**；0.13.0 与 0.14.0 的选择依据只剩 UPX（0.14.0 启动稍慢、ppc64le 不可用）与 #1131 的前瞻方向（0.14.0 将成为官方默认）。

> 注：SIGKILL 探针能确定性收割的前提是 ryuk 收到了过滤器（#1114 修复前无法从客户端侧验证——TC-py 不读 ACK）。若要进一步压随机率，可等 [PR #1124](https://github.com/testcontainers/testcontainers-python/pulls/1124) 合并升级 TC-py，或在测试进程内做一次「读 ACK」式的自检（自建 socket 连 ryuk:8080 发过滤器行并等 `ACK\n` 再放行业务容器）。

## 8. 数据缺口

1. **用户探针 0.14.0 失败轮次的现场快照缺失**（是否首次拉取镜像、当时 `docker port` 输出、colima/qemu/vz 状态、探针脚本）——无法在事后归因到 §3 机制 1 还是机制 2；复核探针的成功说明该失败是概率性的。
2. **随机率无量化**：每版本仅 1~5 轮探针，#1114 类竞态的触发概率（以及 0.8.1 alpine 基底 vs 0.13.0 scratch 基底的窗口差）无文献或统计数据。
3. **官方格局未定**：#1124/#1131 均为 open（2026-10），合并后官方默认与等待逻辑都会变，届时 §0 矩阵需重写；在合并前「4.13+ 死代码等待」官方已知（#1131 正文承认）但无修复时间表。
4. **Colima qemu vs vz 对竞态窗口的差异**：仅有端口映射延迟的定性报告（colima #1424/#71），无面向 ryuk 场景的量化对比。
5. **TC-py ≤3.x 无 ryuk 时代的收割行为**与本题无关，未展开（v3.7.1 全树无 ryuk 引用，已核实）。
