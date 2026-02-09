# Build stage
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /app

# Copy the mvnw wrapper and pom.xml to cache dependencies
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .

# Download dependencies (this layer will be cached unless pom.xml changes)
RUN ./mvnw dependency:go-offline -B

# Copy the source code and build the application
COPY src src
RUN ./mvnw clean package -DskipTests

# Runtime stage
# Using JRE instead of JDK to keep the image lightweight
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

# Copy only the built JAR from the build stage
COPY --from=build /app/target-maven/*.jar app.jar

# Define the entry point
ENTRYPOINT ["java", "-jar", "app.jar"]
