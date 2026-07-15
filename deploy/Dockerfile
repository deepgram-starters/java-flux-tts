# Stage 1: Build custom Caddy with rate limiting module
FROM golang:1.25-alpine AS caddy-builder
WORKDIR /build
RUN go install github.com/caddyserver/xcaddy/cmd/xcaddy@latest
RUN xcaddy build --with github.com/mholt/caddy-ratelimit

# Stage 2: Build frontend
FROM node:24-slim AS frontend-builder
RUN corepack enable && corepack prepare pnpm@9.15.9 --activate
WORKDIR /build
COPY frontend/package.json frontend/pnpm-lock.yaml ./frontend/
RUN cd frontend && pnpm install --frozen-lockfile
COPY frontend/ ./frontend/
RUN cd frontend && pnpm build
RUN sed -i 's|<head>|<head>\n  <base href="{{ .Req.Header.Get "X-Base-Path" }}">|' ./frontend/dist/index.html

# Stage 3: Build the shaded jar
FROM maven:3.9-eclipse-temurin-21 AS jar-builder
WORKDIR /build
COPY pom.xml ./
RUN mvn -q -B dependency:go-offline || true
COPY src ./src
RUN mvn -q -B package -DskipTests

# Stage 4: Runtime
FROM eclipse-temurin:21-jre
COPY --from=caddy-builder /build/caddy /usr/bin/caddy
WORKDIR /app
COPY --from=jar-builder /build/target/*-shaded.jar ./app.jar
COPY *.toml sample.env ./
COPY --from=frontend-builder /build/frontend/dist/ ./frontend/dist/
COPY deploy/Caddyfile /etc/caddy/Caddyfile
COPY deploy/start.sh ./start.sh
RUN chmod +x ./start.sh
ENV PORT=8081
ENV HOST=0.0.0.0
EXPOSE 8080
ENV BACKEND_CMD="java -jar app.jar"
CMD ["./start.sh"]
