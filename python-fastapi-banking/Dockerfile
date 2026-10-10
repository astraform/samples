# syntax=docker/dockerfile:1@sha256:87999aa3d42bdc6bea60565083ee17e86d1f3339802f543c0d03998580f9cb89

FROM ghcr.io/astral-sh/uv:0.11.28@sha256:0f36cb9361a3346885ca3677e3767016687b5a170c1a6b88465ec14aefec90aa AS uv

FROM python:3.12.11-slim@sha256:47ae396f09c1303b8653019811a8498470603d7ffefc29cb07c88f1f8cb3d19f AS builder

WORKDIR /build

COPY --from=uv /uv /uvx /bin/
COPY python-fastapi-banking ./python-fastapi-banking

RUN uv export \
        --locked \
        --no-dev \
        --group build \
        --no-emit-project \
        --format requirements.txt \
        --output-file /build/requirements.lock \
        --directory /build/python-fastapi-banking && \
    python -m pip wheel \
        --require-hashes \
        --only-binary=:all: \
        --wheel-dir /wheels \
        --requirement /build/requirements.lock && \
    python -m pip install \
        --no-cache-dir \
        --no-index \
        --find-links=/wheels \
        setuptools==83.0.0 \
        wheel==0.47.0 && \
    python -m pip wheel \
        --no-index \
        --no-deps \
        --no-build-isolation \
        --wheel-dir /wheels \
        ./python-fastapi-banking


FROM python:3.12.11-slim@sha256:47ae396f09c1303b8653019811a8498470603d7ffefc29cb07c88f1f8cb3d19f

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1
ENV ASTRAFORM_PROVIDER_ARTIFACT_DIGEST_FILE=/opt/astraform/provider-artifact-digest

WORKDIR /app

COPY --from=builder /wheels /wheels

RUN python -m pip install --no-cache-dir --no-index --find-links=/wheels \
        /wheels/banking_remote_domain_python-*.whl && \
    mkdir -p /opt/astraform && \
    python - <<'PY'
import hashlib
import subprocess
import sys
from pathlib import Path

digest = hashlib.sha256()
for wheel in sorted(Path("/wheels").glob("*.whl")):
    digest.update(wheel.name.encode("utf-8"))
    digest.update(b"\0")
    digest.update(wheel.read_bytes())
    digest.update(b"\0")
resolved = subprocess.run(
    [sys.executable, "-m", "pip", "freeze", "--all"],
    check=True,
    stdout=subprocess.PIPE,
    text=True,
).stdout
digest.update(resolved.encode("utf-8"))
Path("/opt/astraform/provider-artifact-digest").write_text(
    f"build-sha256:{digest.hexdigest()}\n",
    encoding="utf-8",
)
PY
RUN rm -rf /wheels

COPY python-fastapi-banking/LICENSE python-fastapi-banking/NOTICE /usr/share/licenses/astraform-python-banking/

EXPOSE 8092

HEALTHCHECK --interval=15s --timeout=5s --start-period=15s --retries=3 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8092/actuator/health', timeout=3).read()" || exit 1

CMD ["uvicorn", "banking_remote_domain_python.main:app", "--host", "0.0.0.0", "--port", "8092"]
