import os
from dataclasses import dataclass

from dotenv import load_dotenv


@dataclass(frozen=True)
class Config:
    database_url: str
    tushare_token: str


def load() -> Config:
    load_dotenv()
    missing = [name for name in ("DATABASE_URL", "TUSHARE_TOKEN") if not os.environ.get(name)]
    if missing:
        raise SystemExit(
            f"缺少环境变量: {', '.join(missing)}"
            "（请在 collector/.env 或进程环境中配置，参考 .env.example）"
        )
    return Config(
        database_url=os.environ["DATABASE_URL"],
        tushare_token=os.environ["TUSHARE_TOKEN"],
    )
