import os
from dataclasses import dataclass

from dotenv import load_dotenv

load_dotenv()


@dataclass(frozen=True)
class Config:
    database_url: str
    tushare_token: str


def load() -> Config:
    return Config(
        database_url=os.environ["DATABASE_URL"],
        tushare_token=os.environ["TUSHARE_TOKEN"],
    )
