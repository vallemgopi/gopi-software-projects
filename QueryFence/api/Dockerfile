FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /app
COPY api/pom.xml api/pom.xml
COPY api/src api/src
COPY evaluation evaluation
RUN --mount=type=cache,target=/root/.m2 mvn -B -f api/pom.xml package -DskipTests
FROM eclipse-temurin:21.0.9_10-jre-alpine
WORKDIR /app
COPY --from=build /app/api/target/queryfence-api-2.0.0.jar app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
