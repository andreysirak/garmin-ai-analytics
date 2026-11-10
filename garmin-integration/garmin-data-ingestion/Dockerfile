FROM maven:3.9-eclipse-temurin-25 AS build

WORKDIR /workspace
COPY garmin-integration/ ./garmin-integration/
RUN mvn -B -ntp -f garmin-integration/pom.xml \
    -pl garmin-data-ingestion/java -am clean package

FROM eclipse-temurin:25-jre

WORKDIR /app
COPY --from=build \
    /workspace/garmin-integration/garmin-data-ingestion/java/target/garmin-data-ingestion.jar \
    /app/garmin-data-ingestion.jar
# Preserve the commands used by the existing Python operators.
RUN ln -s garmin-data-ingestion.jar garmin-daily-summary.jar \
    && ln -s garmin-data-ingestion.jar garmin-sleep.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/garmin-data-ingestion.jar"]
