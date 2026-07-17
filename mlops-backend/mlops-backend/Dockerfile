FROM eclipse-temurin:17-jre-jammy

ENV DEBIAN_FRONTEND=noninteractive
ENV PATH="/opt/python-venv/bin:${PATH}"
ENV PYTHONUNBUFFERED=1
ENV PYTHONDONTWRITEBYTECODE=1

USER root

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        python3 \
        python3-pip \
        python3-venv \
        ca-certificates \
        git \
        curl \
    && python3 -m venv /opt/python-venv \
    && /opt/python-venv/bin/pip install --no-cache-dir --upgrade \
        pip \
        setuptools \
        wheel \
    && /opt/python-venv/bin/pip install --no-cache-dir \
        mlflow==3.1.1 \
        joblib==1.4.2 \
        scikit-learn==1.5.0 \
        pandas==2.2.2 \
        numpy==1.26.4 \
        cloudpickle \
    && ln -sf /opt/python-venv/bin/python /usr/local/bin/python \
    && ln -sf /opt/python-venv/bin/pip /usr/local/bin/pip \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# JAR Spring Boot
COPY mlops-backend/mlops-backend/target/*.jar /app/app.jar

# Script utilisé pour standardiser les artefacts de modèles
COPY mlops-backend/scripts/standardize_model_artifact.py \
     /app/scripts/standardize_model_artifact.py

# Indispensable pour préparer les contextes Kaniko
# et récupérer platform_runner.py
COPY mini-mlops /app/mini-mlops

RUN chmod +x /app/scripts/standardize_model_artifact.py \
    && test -f /app/app.jar \
    && test -f /app/scripts/standardize_model_artifact.py \
    && test -f /app/mini-mlops/src/custom_models/platform_runner.py \
    && grep -q "PLATFORM_RECOVERY" \
       /app/mini-mlops/src/custom_models/platform_runner.py \
    && python --version \
    && python -c "import mlflow, sklearn, pandas, numpy, joblib, cloudpickle; print('Backend Python dependencies OK')" \
    && echo "platform_runner.py successfully included"

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "/app/app.jar"]