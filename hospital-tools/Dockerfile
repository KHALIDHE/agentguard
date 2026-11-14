# Build context is the repository root, so the shared Maven wrapper is available.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY hospital-tools/pom.xml hospital-tools/pom.xml
RUN ./mvnw -B -q -pl hospital-tools dependency:go-offline
COPY hospital-tools/src hospital-tools/src
RUN ./mvnw -B -q -pl hospital-tools -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 1001 app
COPY --from=build /src/hospital-tools/target/*.jar app.jar
USER app
ENTRYPOINT ["java", "-jar", "app.jar"]
