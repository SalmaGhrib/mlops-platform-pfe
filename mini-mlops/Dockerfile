FROM python:3.10-slim

WORKDIR /app

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1
ENV PIP_NO_CACHE_DIR=1
ENV PYTHONPATH=/app

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    git \
    curl \
    ca-certificates \
    libjpeg62-turbo \
    zlib1g \
    libtiff6 \
    libwebp7 \
    libpng16-16 \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt /app/requirements.txt

RUN python -m pip install --upgrade pip setuptools wheel
RUN python -m pip install --no-cache-dir -r /app/requirements.txt

COPY src ./src
COPY kubeflow ./kubeflow

# IMPORTANT : copier le runner corrigé à l'endroit utilisé par les jobs
COPY src/custom_models/platform_runner.py /app/platform_runner.py

CMD ["python", "-m", "src.main"]