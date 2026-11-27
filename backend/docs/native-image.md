# The native image

The deployed backend is a GraalVM native binary. This is what that changes, and
what to do when it breaks.

## Why

| | JVM image | Native image |
| --- | --- | --- |
| Start-up | seconds | well under one |
| Memory request | 768Mi | 256Mi |
| Memory limit | 2Gi | 768Mi |
| Image base | `distroless/java21` | `distroless/base` |
| Build time | ~2 minutes | 10-20 minutes |

The memory numbers are the point. A JVM sizes its heap as a share of the
container limit and then holds it; a native image allocates what it is using.
Start-up matters less than it looks - nothing here scales to zero - but it makes
rolling deploys quick and the start-up probe honest.

## What it costs

**Reflection has to be known at compile time.** Spring's AOT step evaluates the
application context during the build and writes out the reflection, resource and
proxy metadata GraalVM needs, and most libraries ship their own. What it cannot
see - a type named only in a string, a class loaded from configuration - is
absent from the binary and fails when that path first runs.

So the failure mode moved. A jar that passed its tests was the artefact that
deployed; a binary that compiled is not. **`./mvnw verify` runs on the JVM and
proves the code, not the image.** What vouches for the image is the end-to-end
suite running against a deployed PR.

## Building

```sh
cd backend
./mvnw -Pnative -DskipTests native:compile   # needs GraalVM 21; writes target/fam-backend
docker build -t fam-backend .                # or build it through the image
```

`docker compose up` builds the Dockerfile's `dev` stage instead - the jar on a
JVM - because a twenty-minute compile does not belong between a developer and a
running stack. `./mvnw spring-boot:run` is unchanged.

## When something fails only in the native image

The symptom is usually a `ClassNotFoundException`, a
`NoSuchMethodException`, or a serialiser that returns `{}` - all at run time,
all on one endpoint.

1. Reproduce it against the binary rather than the JVM: `./mvnw -Pnative
   -DskipTests native:compile && ./target/fam-backend`.
2. Read the stack trace. `-H:+ReportExceptionStackTraces` is on (see the
   `native-maven-plugin` block in `pom.xml`), so the trace names the type that
   could not be reached.
3. Register it. Prefer `@RegisterReflectionForBinding` on the class that needs
   it - it keeps the reason next to the code - and fall back to a
   `RuntimeHintsRegistrar` for anything that has no single owner.
4. If a whole library is missing metadata, check whether the GraalVM
   reachability repository has it: the `add-reachability-metadata` goal already
   pulls from there, and a version bump is often the fix.

### Pinning a library ahead of Spring Boot

**Don't, for anything Boot has native support for.** Boot's native integration
reaches into library internals, and those are not API-stable across minor
versions. Flyway was pinned six months ahead of the version Boot manages, to
silence a "Flyway upgrade recommended" warning about PostgreSQL 18; it compiled
cleanly and then died at startup with a `NoSuchMethodError` from
`NativeImageResourceProviderCustomizer`, which constructs a Flyway `Scanner`
whose signature had moved.

The JVM never runs that code, so the whole test suite passed. Nothing caught it
until a pod refused to start. The version is now whatever Boot manages - see the
comment in `pom.xml` - and the warning is the price.

### Hints this application already needs

`NativeRuntimeHints` (registered from `FamApiApplication`) holds them, one entry
per reason:

- **`UUID[]` and `String[]`, unsafe-allocated.** Hibernate builds a multi-id
  loader for every entity, and that allocates an array of the entity's id type
  reflectively while the `SessionFactory` is built. Without the hint the
  application fails at start-up - not on a query - with "Class java.util.UUID[]
  is instantiated reflectively but was never registered". **An entity added with
  an id of some other type needs its array added there.**

- **The CSS client's response types**, registered for binding. `TokenResponse`,
  `CssIntegrationDto`, `CssRoleDto` and `CssUserDto` are read from upstream
  responses inside `CssApiService` and named in no controller signature, so AOT
  cannot see them. Without the hints Jackson has no constructor to call, the
  parse returns null, and the caller reports "CSS token response contained no
  access_token" - every screen that lists applications answers 502.
- **The token source's records**, through `TokenSourceRuntimeHints` rather than
  the annotation. `@RegisterReflectionForBinding` is only read from beans, and
  `ClientCredentialsTokenSource` is constructed directly, so the annotation was
  ignored without complaint. **If a hint appears to do nothing, check whether
  the class carrying it is a bean.**

### The parts most likely to need it here

- **springdoc / swagger-ui.** The most dynamic dependency in the build. It
  publishes its own metadata, but a springdoc upgrade is the change most likely
  to break `/v3/api-docs` in native only. The e2e smoke test asserts that
  endpoint answers, which is the check that would catch it.
- **Jakarta Mail.** Resource-driven (`mime.types`, provider files). Spring
  registers those, and a failure would be at send time rather than at boot -
  where `AccessGrantedEmailService` already records the outcome on the result
  rather than failing the grant.
- **JSONB columns.** `FamPrivilegeChangeAudit` writes DTOs through Jackson into
  `jsonb`; those DTO types are registered by AOT today because they are reachable
  from controllers. A type written to JSONB but never named in an API signature
  would not be.

## Where the pieces are

| File | What it does |
| --- | --- |
| `pom.xml` | Declares `native-maven-plugin`; the `native` profile itself comes from `spring-boot-starter-parent`. The plugin has no execution bound to a phase on purpose - that would make every ordinary `mvn package` try to compile a binary - so the Dockerfile names the `native:compile` goal instead |
| `Dockerfile` | `deps` → `build-jar`/`build-native` → `dev`/`deploy` stages. The deploy stage also copies `libz.so.1` in: the binary links zlib and distroless/base does not ship it, which the build logs as `ldd` output on every compile |
| `openshift.deploy.yml` | The reduced memory request and limit, and the shorter start-up probe |
| `FamApiApplication` | Routes a `healthcheck` argument to `HealthCheck`, because the image has no `java` to run a second class with; imports `NativeRuntimeHints` |
| `NativeRuntimeHints` | The reflective accesses AOT does not work out on its own, each with the code path that needs it |
| `TokenSourceRuntimeHints` | The same, for the token records - a registrar because the class that parses them is not a bean |
| `.github/workflows/pr-open.yml` | The 40-minute build timeout the compile needs |
