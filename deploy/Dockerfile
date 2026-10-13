# Build stage: the same image scripts/mvn.sh uses, so the jar is built exactly as it is tested.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
RUN mvn -q -B -DskipTests package

# Run stage: a JRE only, and a user that is not root.
FROM eclipse-temurin:21-jre
COPY --from=build /src/sales-app/target/sales-app-0.1.0.jar /app.jar
USER 10001
ENTRYPOINT ["java", "-jar", "/app.jar"]
