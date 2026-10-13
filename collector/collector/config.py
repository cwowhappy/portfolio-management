import logging
import os
from dataclasses import dataclass

from dotenv import load_dotenv

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class Config:
    database_url: str
    tushare_token: str


def env_float(name: str, default: float) -> float:
    """读可选的 env 浮点覆盖（如限速间隔），缺失/空/非法回退默认值并留痕。"""
    raw = os.environ.get(name, "").strip()
    if not raw:
        return default
    try:
        return float(raw)
    except ValueError:
        logger.warning("环境变量 %s=%r 非浮点，回退默认 %s", name, raw, default)
        return default


_ENV_NAMES = {"database_url": "DATABASE_URL", "tushare_token": "TUSHARE_TOKEN"}


def load(required=("database_url", "tushare_token")) -> Config:
    """配置校验 fail-fast 且按命令分级（规范 C3）：required 声明本命令所需的配置键——
    run/backfill 等采集命令需要 database_url + tushare_token；list/history/seed 等
    只读 DB 命令只需 database_url，不强制数据源密钥。缺配置报错列出缺失项与修复指引。
    """
    load_dotenv()
    missing = [_ENV_NAMES[name] for name in required if not os.environ.get(_ENV_NAMES[name])]
    if missing:
        raise SystemExit(
            f"缺少环境变量: {', '.join(missing)}（请在 collector/.env 或进程环境中配置，参考 .env.example）"
        )
    return Config(
        database_url=os.environ.get("DATABASE_URL", ""),
        tushare_token=os.environ.get("TUSHARE_TOKEN", ""),
    )
