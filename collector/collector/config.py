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


def load() -> Config:
    load_dotenv()
    missing = [name for name in ("DATABASE_URL", "TUSHARE_TOKEN") if not os.environ.get(name)]
    if missing:
        raise SystemExit(
            f"缺少环境变量: {', '.join(missing)}（请在 collector/.env 或进程环境中配置，参考 .env.example）"
        )
    return Config(
        database_url=os.environ["DATABASE_URL"],
        tushare_token=os.environ["TUSHARE_TOKEN"],
    )
