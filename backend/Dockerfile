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
ENTRYPOINT ["java", "-jar", "app.jar"]
