# syntax=docker/dockerfile:1@sha256:87999aa3d42bdc6bea60565083ee17e86d1f3339802f543c0d03998580f9cb89

FROM ghcr.io/astral-sh/uv:0.11.28@sha256:0f36cb9361a3346885ca3677e3767016687b5a170c1a6b88465ec14aefec90aa AS uv

FROM python:3.12.11-slim@sha256:47ae396f09c1303b8653019811a8498470603d7ffefc29cb07c88f1f8cb3d19f AS builder

WORKDIR /build
COPY --from=uv /uv /uvx /bin/
COPY banking-advice-agent/pyproject.toml banking-advice-agent/uv.lock ./
RUN uv export \
        --locked \
        --no-dev \
        --no-emit-project \
        --format requirements.txt \
        --output-file requirements.lock && \
    python -m pip wheel \
        --require-hashes \
        --only-binary=:all: \
        --wheel-dir /wheels \
        --requirement requirements.lock

FROM python:3.12.11-slim@sha256:47ae396f09c1303b8653019811a8498470603d7ffefc29cb07c88f1f8cb3d19f

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1
ENV BANKING_ADVICE_AGENT_URL=http://localhost:8099

WORKDIR /app
COPY --from=builder /wheels /wheels
COPY --from=builder /build/requirements.lock /tmp/requirements.lock
RUN python -m pip install \
        --no-cache-dir --no-index --find-links=/wheels \
        --require-hashes --requirement /tmp/requirements.lock && \
    rm -rf /wheels /tmp/requirements.lock && \
    useradd --uid 10001 --create-home astraform

COPY banking-advice-agent/app.py banking-advice-agent/run-customer.py ./
COPY banking-advice-agent/input.schema.json banking-advice-agent/bank-backend.input.schema.json banking-advice-agent/output.schema.json ./
COPY banking-advice-agent/sample-configuration.json banking-advice-agent/issued-statement-configuration.json banking-advice-agent/three-month-configuration.json ./

COPY banking-advice-agent/LICENSE banking-advice-agent/NOTICE /usr/share/licenses/astraform-banking-advice/

USER 10001
EXPOSE 8099
HEALTHCHECK --interval=15s --timeout=5s --start-period=15s --retries=3 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8099/health', timeout=3).read()" || exit 1

CMD ["uvicorn", "app:create_app", "--factory", "--host", "0.0.0.0", "--port", "8099"]
