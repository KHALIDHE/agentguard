# Build context is the repository root, so the shared Maven wrapper is available.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY gateway/pom.xml gateway/pom.xml
RUN ./mvnw -B -q -pl gateway dependency:go-offline
COPY gateway/src gateway/src
RUN ./mvnw -B -q -pl gateway -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 1001 app
COPY --from=build /src/gateway/target/*.jar app.jar
USER app
ENTRYPOINT ["java", "-jar", "app.jar"]
