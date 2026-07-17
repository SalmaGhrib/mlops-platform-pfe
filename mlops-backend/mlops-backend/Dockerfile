FROM eclipse-temurin:17-jre-jammy

ENV DEBIAN_FRONTEND=noninteractive
ENV PATH="/opt/python-venv/bin:${PATH}"

USER root

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       python3 \
       python3-pip \
       python3-venv \
       ca-certificates \
    && python3 -m venv /opt/python-venv \
    && /opt/python-venv/bin/pip install --no-cache-dir --upgrade pip setuptools wheel \
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

COPY mlops-backend/mlops-backend/target/*.jar /app/app.jar

COPY mlops-backend/scripts/standardize_model_artifact.py \
     /app/scripts/standardize_model_artifact.py

RUN chmod +x /app/scripts/standardize_model_artifact.py \
    && python --version \
    && python -c "import mlflow, sklearn, joblib, pandas, numpy; print('Python dependencies OK')"

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "/app/app.jar"]