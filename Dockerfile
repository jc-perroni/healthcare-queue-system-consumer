# syntax=docker/dockerfile:1.7

# Build stage
FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /workspace

# Copy wrapper + pom first to maximize Docker layer cache
COPY consumer/.mvn/ consumer/.mvn/
COPY consumer/mvnw consumer/pom.xml consumer/

WORKDIR /workspace/consumer
RUN chmod +x mvnw \
  && ./mvnw -q -DskipTests dependency:go-offline

# Copy sources and build
COPY consumer/src/ ./src/
RUN ./mvnw -q -DskipTests package \
  && cp target/*.jar /workspace/app.jar


# Runtime stage
FROM eclipse-temurin:21-jre

# Run as non-root
RUN useradd --system --uid 10001 --create-home appuser

WORKDIR /app
COPY --from=build /workspace/app.jar /app/app.jar

EXPOSE 8081

ENV JAVA_OPTS=""
ENV SERVER_PORT=8081

# Defaults para rodar a IMAGEM com o stack (Kafka/Postgres/Redis) exposto no HOST.
# macOS/Windows (Docker Desktop): host.docker.internal resolve para o host.
# Linux: use "--add-host=host.docker.internal:host-gateway" no docker run.
ENV KAFKA_BOOTSTRAP_SERVERS=host.docker.internal:29092 \
  KAFKA_TOPIC_EVENTS=healthcare.queue.events.v1 \
  KAFKA_CONSUMER_GROUP=healthcare-queue-consumer \
  DB_URL=jdbc:postgresql://host.docker.internal:5432/healthcoredb \
  DB_USERNAME=postgres \
  DB_PASSWORD=pass123 \
  DB_DRIVER=org.postgresql.Driver \
  DB_DIALECT=org.hibernate.dialect.PostgreSQLDialect \
  REDIS_HOST=host.docker.internal \
  REDIS_PORT=6379 \
  APP_LOG_LEVEL=INFO

USER 10001

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
