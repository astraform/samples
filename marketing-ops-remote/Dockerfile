# syntax=docker/dockerfile:1@sha256:87999aa3d42bdc6bea60565083ee17e86d1f3339802f543c0d03998580f9cb89
FROM maven:3.9.11-eclipse-temurin-21@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237 AS build
WORKDIR /app

COPY marketing-ops-remote/pom.xml ./marketing-ops-remote/pom.xml
WORKDIR /app/marketing-ops-remote
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp dependency:go-offline
COPY marketing-ops-remote/src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp package -DskipTests -Dcyclonedx.skip=true \
 && cp target/marketing-ops-remote.jar /tmp/app.jar

FROM eclipse-temurin:21-jre-alpine@sha256:3f08b13888f595cc49edabea7250ba69499ba25602b267da591720769400e08c
WORKDIR /app

COPY --from=build /tmp/app.jar app.jar

EXPOSE 8090/tcp
HEALTHCHECK --interval=15s --timeout=5s --start-period=20s --retries=3 \
  CMD wget -q -T 4 -O /dev/null http://localhost:8090/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
