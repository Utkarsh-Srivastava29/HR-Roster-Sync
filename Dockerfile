# --- build ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

# --- run ---
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app && mkdir -p /data && chown app /data
USER app
WORKDIR /app
COPY --from=build /src/target/hr-roster-sync-1.0.0.jar app.jar
ENV ROSTER_DATA_DIR=/data SPRING_PROFILES_ACTIVE=postgres
EXPOSE 8080 9091
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
