# syntax=docker/dockerfile:1

### Dependencies
#
# GraalVM, because the deployed image is a native binary rather than a jar on a
# JVM. The application starts in a fraction of a second and holds a fraction of
# the memory, which is what OpenShift is paying for: a pod that used to reserve
# 768Mi to idle now reserves 256Mi, and a rolling deploy no longer spends a
# minute and a half waiting for JVMs to warm up.
#
# The same JDK builds the ordinary jar for local work, so there is one toolchain
# here rather than two.
FROM ghcr.io/graalvm/native-image-community:21 AS deps
WORKDIR /app

# Resolved as their own layer so source-only changes stay cached.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -ntp dependency:go-offline

COPY src/ src/

# Where zlib is in this image.
#
# The deploy stage is distroless/base, which carries glibc, OpenSSL and little
# else - and a GraalVM binary links zlib, so it exits on the first line with
# "libz.so.1: cannot open shared object file". The library is copied across
# below; this proves the path now, in a stage that takes a minute, rather than
# after a twenty-minute compile.
RUN test -f /usr/lib64/libz.so.1 || { \
      echo "libz.so.1 is not at /usr/lib64 - the COPY in the deploy stage needs the real path:"; \
      ls -l /usr/lib64/libz* /lib64/libz* /usr/lib/*/libz* 2>/dev/null; \
      exit 1; \
    }

### The jar, for local development
FROM deps AS build-jar
RUN ./mvnw -B -ntp -DskipTests package && \
    java -Djarmode=tools -jar target/*.jar extract --layers \
      --application-filename app.jar --destination extracted

### The native binary, for deployment
#
# `native:compile`, not `package`. The goal has to be named: with -Pnative alone,
# `package` runs Spring's AOT processing and then repackages the jar, which
# finishes in seconds and writes no binary at all - the stage then "succeeds"
# and the COPY below fails looking for a file nothing produced.
#
# The goal forks its own `package` run first, so AOT still happens: the context
# is evaluated at build time and the reflection, resource and proxy metadata
# GraalVM needs is written out. Then it compiles, which is the slow part of the
# build - ten to twenty minutes, and hungry for memory.
#
# The compiler's heap is left to size itself against the container's limit,
# which is what it does by default. If a runner ever dies here for want of
# memory, cap it with -J-Xmx in the plugin's buildArgs rather than guessing at a
# number that has to suit both CI and a laptop.
FROM deps AS build-native
# The `test` is the guard: a compile that quietly produces nothing should fail
# here, naming the stage that did it, rather than three stages later as a COPY
# that cannot find its source.
RUN ./mvnw -B -ntp -Pnative -DskipTests native:compile && test -x target/fam-backend \
    && echo "--- shared libraries the binary needs:" && ldd target/fam-backend

### Local development image
#
# What docker-compose builds (`target: dev`). A jar on a JVM, because a native
# compile between `docker compose up` and a running stack would cost twenty
# minutes and teach everybody to avoid compose. Nothing deploys this.
FROM gcr.io/distroless/java21-debian12:nonroot AS dev
WORKDIR /app

COPY --from=build-jar /app/extracted/dependencies/ ./
COPY --from=build-jar /app/extracted/spring-boot-loader/ ./
COPY --from=build-jar /app/extracted/snapshot-dependencies/ ./
COPY --from=build-jar /app/extracted/application/ ./

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:InitialRAMPercentage=50 -Djava.security.egd=file:/dev/./urandom"
EXPOSE 8080
USER nonroot
ENTRYPOINT ["java", "-jar", "app.jar"]

### Deploy
#
# Last, so it is what an unqualified build produces - CI names no target.
#
# base-debian12, not the java21 image: there is no JVM to carry any more. The
# binary is not fully static - it links glibc and the system TLS libraries - so
# this cannot go all the way down to `static`.
FROM gcr.io/distroless/base-debian12:nonroot AS deploy
WORKDIR /app

COPY --from=build-native /app/target/fam-backend ./fam-backend

# zlib, which the binary links and this image does not ship. Into the multiarch
# directory rather than /usr/lib64: that is where Debian's loader looks, and the
# build image's own layout (Oracle Linux) is not this image's.
#
# The alternative is distroless/java-base, which carries the libraries a JVM
# wants - zlib among them - but it exists to host a JVM this image does not have.
COPY --from=deps /usr/lib64/libz.so.1 /usr/lib/x86_64-linux-gnu/libz.so.1

EXPOSE 8080
USER nonroot

# No JAVA_TOOL_OPTIONS: heap percentages meant something to a JVM sizing itself
# against the container's limit. A native image manages its own heap and honours
# the cgroup limit directly, so there is nothing here to tune.

# The binary probes itself - see FamApiApplication. Distroless has no shell,
# curl or wget, and there is no `java` to run a helper class with any more. The
# start period is short because the application is listening almost immediately;
# it used to allow a minute for the JVM.
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD ["/app/fam-backend", "healthcheck"]

ENTRYPOINT ["/app/fam-backend"]
