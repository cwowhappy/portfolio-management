"""conftest Ryuk 收割配置守卫（2026-10-16 P0 探针后落地，机制与证据见
docs/technology/architecture/03-后端测试架构.md「Ryuk 收割」节）。

不变量：collector 测试会话不得禁用 Ryuk（禁用 = 客户端被 kill 后容器永久泄漏，
已实证积累 288 个）；socket override 与 ryuk 版本钉由 conftest setdefault 注入，
外部显式设置时以外部为准（断言存在性而非具体值，避免误伤合法覆盖）。
"""

import os


def test_ryuk_reap_enabled_and_configured():
    assert os.environ.get("TESTCONTAINERS_RYUK_DISABLED") != "true"
    # VM 内标准路径恒正确（conftest 强制覆盖外部宿主路径值），等值断言使偏离必须显式改此处
    assert os.environ.get("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE") == "/var/run/docker.sock"
    # python TC 的镜像变量名（config.py 单例读 RYUK_CONTAINER_IMAGE，非 TESTCONTAINERS_ 前缀）
    assert os.environ.get("RYUK_CONTAINER_IMAGE") == "testcontainers/ryuk:0.13.0"
