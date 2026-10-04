FROM maven:3.9-eclipse-temurin-21-alpine AS build

WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package

FROM gcr.io/distroless/java21-debian12:nonroot AS runtime

WORKDIR /app
COPY --from=build --chown=65532:65532 /workspace/target/seat-reservation-0.0.1-SNAPSHOT.jar /app/app.jar

ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:InitialRAMPercentage=25.0 -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

USER 65532:65532
EXPOSE 8080

ENTRYPOINT ["/usr/bin/java", "-jar", "/app/app.jar"]
