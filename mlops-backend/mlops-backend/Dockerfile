FROM eclipse-temurin:17-jre

USER root

RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 python3-pip \
    && ln -sf /usr/bin/python3 /usr/local/bin/python \
    && python3 -m pip install --no-cache-dir --break-system-packages \
       mlflow==3.1.1 joblib==1.4.2 scikit-learn==1.5.0 pandas==2.2.2 numpy==1.26.4 cloudpickle \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY mlops-backend/mlops-backend/target/*.jar app.jar
COPY mini-mlops /app/mini-mlops

RUN mkdir -p /app/scripts
COPY mlops-backend/scripts/standardize_model_artifact.py /app/scripts/standardize_model_artifact.py

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
