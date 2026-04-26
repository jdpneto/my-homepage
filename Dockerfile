FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .
RUN chmod +x mvnw && ./mvnw dependency:resolve
COPY src src
RUN ./mvnw package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN apk add --no-cache ffmpeg
COPY --from=build /app/target/*.jar app.jar
RUN mkdir -p /app/uploads /app/webdav /app/gallery
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
