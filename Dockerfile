# Stage 1: build
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /app

# pom and wrapper first, so the dependency layer survives a source-only change
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B dependency:go-offline

COPY src src
# Tests run in CI against the whole matrix; repeating them here would double the
# feedback loop without adding a signal.
RUN ./mvnw -B package -DskipTests

# Stage 2: runtime
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

# Non-root. A container that does not need root should not have it: nothing here
# writes outside /tmp or binds a privileged port.
RUN addgroup -S -g 1001 app && adduser -S -u 1001 -G app app

COPY --from=build --chown=app:app /app/target-maven/*.jar app.jar

USER app:app

EXPOSE 8081

# MaxRAMPercentage rather than a fixed -Xmx: the JVM then sizes the heap from the
# container's memory limit, so changing the Kubernetes limit is enough.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
