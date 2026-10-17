# syntax=docker/dockerfile:1

### Build
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app

# Resolve dependencies as their own layer so source-only changes stay cached.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -ntp dependency:go-offline

COPY src/ src/
RUN ./mvnw -B -ntp clean package -DskipTests

# Explode the jar so the runtime image can use Spring Boot's layered layout,
# keeping dependency layers stable across application-only rebuilds.
#
# --application-filename fixes the name the ENTRYPOINT below launches. Without
# it the extracted jar keeps the uber-jar's name, which carries the version -
# so the image would only start until the next version bump.
RUN java -Djarmode=tools -jar target/*.jar extract --layers \
      --application-filename app.jar --destination extracted

### Deploy
FROM gcr.io/distroless/java21-debian12:nonroot AS deploy
WORKDIR /app

COPY --from=build /app/extracted/dependencies/ ./
COPY --from=build /app/extracted/spring-boot-loader/ ./
COPY --from=build /app/extracted/snapshot-dependencies/ ./
COPY --from=build /app/extracted/application/ ./

# Container-aware heap sizing; OpenShift sets the memory limit, not the image.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:InitialRAMPercentage=50 -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8080
USER nonroot

# Distroless has no shell and no curl or wget, so the probe is a class in the
# application's own jar using java.net.http. OpenShift ignores HEALTHCHECK and
# runs its own probes; this serves anyone running the image directly. The JVM
# needs a moment to come up, hence the start period. The heap flags are not
# tuning: they undo JAVA_TOOL_OPTIONS above, which would otherwise have this
# throwaway JVM reserve half the container's memory alongside the application.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD ["java", "-Xms8m", "-Xmx32m", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", \
       "-cp", "app.jar", "ca.bc.gov.nrs.fam.HealthCheck"]

ENTRYPOINT ["java", "-jar", "app.jar"]
