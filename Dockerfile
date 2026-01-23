# SENTINEL Intelligence Platform - Production Dockerfile
# Hardened container for government/enterprise deployments

# Build stage
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

# Copy Gradle wrapper and config
COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .

# Make gradlew executable
RUN chmod +x gradlew

# Copy source code
COPY src src

# Build argument for edition selection
ARG EDITION=government

# Build the application
RUN ./gradlew bootJar -Pedition=${EDITION} --no-daemon

# Runtime stage - minimal hardened image
FROM eclipse-temurin:21-jre-alpine

# Security: Run as non-root user
RUN addgroup -g 1001 sentinel && \
    adduser -u 1001 -G sentinel -s /bin/sh -D sentinel

WORKDIR /app

# Copy only the built JAR
ARG EDITION=government
COPY --from=builder /app/build/libs/sentinel-${EDITION}-*.jar app.jar

# Set ownership
RUN chown -R sentinel:sentinel /app

# Switch to non-root user
USER sentinel

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget --quiet --tries=1 --spider http://localhost:8080/api/health || exit 1

# JVM memory settings - adjust based on deployment
ENV JAVA_OPTS="-Xms512m -Xmx2g -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

# Default environment variables (set via deployment)
ENV SPRING_DATA_MONGODB_URI=mongodb://mongo:27017/sentinel
ENV SPRING_AI_OLLAMA_BASE_URL=http://ollama:11434

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar --spring.profiles.active=${APP_PROFILE}"]
