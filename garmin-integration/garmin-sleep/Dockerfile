FROM maven:3.9-eclipse-temurin-25 AS build

WORKDIR /workspace
COPY garmin-integration/ ./garmin-integration/
RUN mvn -B -ntp -f garmin-integration/pom.xml \
    -pl garmin-sleep/java -am clean package

FROM eclipse-temurin:25-jre

WORKDIR /app
COPY --from=build \
    /workspace/garmin-integration/garmin-sleep/java/target/garmin-sleep.jar \
    /app/garmin-sleep.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/garmin-sleep.jar"]
