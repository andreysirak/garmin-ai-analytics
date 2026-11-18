FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY garmin-chat/backend/pom.xml ./pom.xml
COPY garmin-chat/backend/src ./src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /workspace/target/garmin-chat-0.1.0-SNAPSHOT.jar /app/garmin-chat.jar
USER 10001:10001
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/garmin-chat.jar"]
