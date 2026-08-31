# syntax=docker/dockerfile:1.7
FROM maven:3.9.11-eclipse-temurin-21-alpine AS build
WORKDIR /src
COPY pom.xml ./
RUN mvn -B --no-transfer-progress dependency:go-offline
COPY src ./src
RUN mvn -B --no-transfer-progress clean package -DskipTests
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S -g 1001 pvdd && adduser -S -u 1001 -G pvdd pvdd \
 && chgrp -R 0 /app /tmp && chmod -R g=u /app /tmp
COPY --from=build --chown=1001:0 /src/target/pvdd-backend-*.jar /app/app.jar
USER 1001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
