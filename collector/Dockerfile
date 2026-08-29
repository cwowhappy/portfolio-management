FROM python:3.12-slim

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt \
    && apt-get update && apt-get install -y --no-install-recommends tzdata && rm -rf /var/lib/apt/lists/* \
    && useradd --create-home appuser

COPY collector/ collector/
COPY migrations/ migrations/
COPY tasks/ tasks/

# 迁移由 collector.scheduler.jobs.main() 启动时执行（alembic upgrade head），此处不重复。
USER appuser
CMD ["python", "-m", "collector.scheduler.jobs"]
