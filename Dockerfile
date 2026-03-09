# ─── Stage 1: Build ───────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app

# Cache dependency layer — only re-downloads when pom.xml changes
COPY pom.xml .
RUN mvn dependency:go-offline -q || true

COPY src src
RUN ./mvnw clean package -DskipTests -q || mvn clean package -DskipTests -q

# ─── Stage 2: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Non-root user for security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

# Copy the fat jar from the build stage
COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8080

# Kubernetes / Docker Compose health probe — hits the public /actuator/health endpoint
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]
