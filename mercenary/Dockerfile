# STAGE 1: Build the Application
FROM gradle:8.5-jdk21 AS build
WORKDIR /app
COPY . .
# Skip tests to speed up the build
RUN gradle clean build -x test --no-daemon

# STAGE 2: Run the Application
# CHANGED: Switched from 'openjdk' (deprecated) to 'eclipse-temurin' (standard)
FROM eclipse-temurin:21-jdk
WORKDIR /app
# Copy the built jar file from Stage 1
COPY --from=build /app/build/libs/*.jar app.jar

# Expose the port
EXPOSE 8080

# The command to start the app
ENTRYPOINT ["java", "-jar", "app.jar"]