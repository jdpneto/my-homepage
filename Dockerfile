FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .
RUN chmod +x mvnw && ./mvnw dependency:resolve
COPY src src
RUN ./mvnw package -DskipTests

# Runtime: Amazon Corretto 21 (shipped the CVE-2026-114938 "Java4Shell" fix,
# 21.0.13.1.2, before Temurin). Floating tag + `build --pull` in deploy.sh
# picks up future JDK security updates on every deploy.
FROM amazoncorretto:21-alpine
WORKDIR /app
RUN apk upgrade --no-cache && apk add --no-cache ffmpeg
COPY --from=build /app/target/*.jar app.jar
RUN mkdir -p /app/uploads /app/webdav /app/gallery
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
