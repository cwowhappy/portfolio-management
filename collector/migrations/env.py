import os
from logging.config import fileConfig

from alembic import context
from sqlalchemy import create_engine

config = context.config
if config.config_file_name is not None:
    # disable_existing_loggers=False：迁移不应禁用宿主进程已存在的 logger，
    # 否则（如测试中）会破坏 caplog 等对既有 logger 的捕获
    fileConfig(config.config_file_name, disable_existing_loggers=False)

url = os.environ.get("DATABASE_URL") or config.get_main_option("sqlalchemy.url")
if url and url.startswith("postgresql://"):
    url = url.replace("postgresql://", "postgresql+psycopg://", 1)


def run_migrations_offline() -> None:
    context.configure(url=url, literal_binds=True)
    with context.begin_transaction():
        context.run_migrations()


def run_migrations_online() -> None:
    engine = create_engine(url)
    with engine.connect() as connection:
        context.configure(connection=connection)
        with context.begin_transaction():
            context.run_migrations()


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
