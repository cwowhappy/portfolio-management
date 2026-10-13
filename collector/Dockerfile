FROM python:3.12-slim

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt \
    && apt-get update && apt-get install -y --no-install-recommends tzdata && rm -rf /var/lib/apt/lists/* \
    && useradd --create-home appuser

COPY collector/ collector/
COPY migrations/ migrations/
COPY tasks/ tasks/

# 时区兜底：compose 部署已注入 TZ（docker-compose.yml collector 服务），单独 docker run
# 时以本处为准——cron 表达式按 Asia/Shanghai 解释，避免按 UTC 错开采集窗口。
ENV TZ=Asia/Shanghai

# 迁移由 collector.scheduler.jobs.main() 启动时执行（alembic upgrade head），此处不重复。
USER appuser

# 健康探针：调度器是 BlockingScheduler 长驻进程，无 HTTP 端口可探；探进程存活
# （PID 1 即调度进程本身，os.kill(pid, 0) 存在性检查）。进程内 job 异常有 _run_task_job
# 兜底留痕（日志 + run 记录），进程级假死由 restart 策略覆盖。
HEALTHCHECK --interval=60s --timeout=5s --start-period=60s --retries=3 \
    CMD ["python", "-c", "import os; os.kill(1, 0)"]

CMD ["python", "-m", "collector.scheduler.jobs"]
