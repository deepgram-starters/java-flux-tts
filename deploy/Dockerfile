# Backend-only image for the Java Flux TTS starter.
#
# Once the frontend submodule (flux-tts-html) exists, replace this with the
# Caddy + frontend multi-stage build from the java-flux starter so static assets
# and rate limiting are served at the edge (internal_port 8080).

# Stage 1: build the shaded jar
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build
COPY pom.xml ./
# Pre-fetch dependencies for better layer caching
RUN mvn -q -B dependency:go-offline || true
COPY src ./src
RUN mvn -q -B package -DskipTests

# Stage 2: runtime
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /build/target/java-flux-tts-1.0.0-shaded.jar ./app.jar
COPY deepgram.toml ./deepgram.toml

ENV PORT=8081
ENV HOST=0.0.0.0
EXPOSE 8081

CMD ["java", "-jar", "app.jar"]
