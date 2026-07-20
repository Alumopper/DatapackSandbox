FROM eclipse-temurin:25-jdk-noble AS build

WORKDIR /source
COPY . .
RUN chmod +x ./gradlew && ./gradlew :cli:fatJar :playground-api:installDist --no-daemon

FROM eclipse-temurin:25-jre-noble

RUN useradd --create-home --uid 10001 playground
WORKDIR /app
COPY --from=build /source/playground-api/build/install/playground-api /app/playground-api
COPY --from=build /source/cli/build/libs/datapack-sandbox-cli.jar /app/datapack-sandbox-cli.jar

ENV DPS_CLI_JAR=/app/datapack-sandbox-cli.jar \
    DPS_HOST=0.0.0.0 \
    DPS_PORT=8080

USER playground
EXPOSE 8080
ENTRYPOINT ["/app/playground-api/bin/playground-api"]
