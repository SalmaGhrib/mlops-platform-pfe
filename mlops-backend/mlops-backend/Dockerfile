FROM eclipse-temurin:17-jre

WORKDIR /app

COPY target/*.jar app.jar

COPY mini-mlops /app/mini-mlops

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]