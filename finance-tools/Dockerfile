# Build context is the repository root, so the shared Maven wrapper and parent pom are available.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN ./mvnw -B -q -pl finance-tools -am -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 1001 app
COPY --from=build /src/finance-tools/target/*.jar app.jar
USER app
ENTRYPOINT ["java", "-jar", "app.jar"]
