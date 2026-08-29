FROM python:3.12-slim

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt \
    && apt-get update && apt-get install -y --no-install-recommends tzdata && rm -rf /var/lib/apt/lists/*

COPY collector/ collector/
COPY migrations/ migrations/
COPY tasks/ tasks/

CMD ["sh", "-c", "alembic -c migrations/alembic.ini upgrade head && python -m collector.scheduler.jobs"]
