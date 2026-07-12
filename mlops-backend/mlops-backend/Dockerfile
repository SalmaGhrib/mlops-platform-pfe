FROM eclipse-temurin:17-jre

WORKDIR /app

COPY mlops-backend/mlops-backend/target/*.jar app.jar

COPY mini-mlops /app/mini-mlops

RUN mkdir -p /app/scripts
COPY mlops-backend/scripts/standardize_model_artifact.py /app/scripts/standardize_model_artifact.py

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]