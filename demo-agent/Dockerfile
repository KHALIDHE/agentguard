# Build context is the repository root, so the shared Maven wrapper is available.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY demo-agent/pom.xml demo-agent/pom.xml
RUN ./mvnw -B -q -pl demo-agent dependency:go-offline
COPY demo-agent/src demo-agent/src
RUN ./mvnw -B -q -pl demo-agent -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 1001 app
COPY --from=build /src/demo-agent/target/*.jar app.jar
USER app
ENTRYPOINT ["java", "-jar", "app.jar"]
