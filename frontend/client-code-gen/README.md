# FAM API client generation

TypeScript clients for the FAM API, generated with
[openapi-generator](https://openapi-generator.tech/docs/generators/typescript-axios)
(`typescript-axios`). Requires Node and a JRE/JDK; a Docker-based script is
provided as an alternative.

This directory lives **inside `frontend/`**, not beside it. The frontend image is
built with `frontend/` as its Docker context, so a sibling directory would fall
outside the context and the `file:` dependencies would not resolve.

## One client

Upstream FAM ran two APIs and shipped two clients (`fam-app-acsctl-api` and
`fam-admin-mgmt-api`). The backend is a single service now, so there is a single
client: **`gen/fam-api`**, generated from `fam-openapi.json`.

The frozen Python-era specs and their generated clients have been removed.

## Regenerating the client

Unlike the legacy specs — which were copied by hand out of FastAPI's
`/openapi.json` — the merged spec is produced from the **running backend**, so a
controller change cannot silently drift from the client.

```sh
# 1. Regenerate the spec (writes ./fam-openapi.json)
cd ../../backend && ./mvnw test -Dtest=OpenApiSpecGeneratorTest

# 2. Regenerate the client
cd ../frontend/client-code-gen && npm ci --ignore-scripts && npm run gen-api-client
```

`OpenApiSpecGeneratorTest` boots the app against H2, so it needs neither Docker
nor PostgreSQL. It asserts that every controller appears in the document and that
properties are `snake_case`.

> **Naming.** springdoc builds schemas from Java property names and ignores
> Jackson's `SNAKE_CASE` strategy, so the document advertised `userName` while the
> API serialises `user_name` — a mismatch that would have produced a client
> unable to read any response field. `OpenApiConfiguration` registers a
> `ModelResolver` bound to the application's `ObjectMapper` to correct it, and the
> generator test guards against regression.

## After regenerating

The generator overwrites the client's `package.json`, so re-apply the axios
`peerDependencies` change:

```jsonc
// replace
"dependencies": { "axios": "..." }
// with
"peerDependencies": { "axios": "^1.16.0" }
```

TypeScript treats axios types as distinct when they resolve through different
module paths (the frontend's `node_modules` vs the client's), even at compatible
versions. Declaring axios as a peer makes the frontend the single owner.

Then reinstall:

```sh
cd .. && npm run install-frontend
```

## Using the client

```ts
import { FAMApplicationsApi } from "fam-api";

// The second argument must be an empty string, not null or undefined.
const api = new FAMApplicationsApi(undefined, "", axiosInstance);
```

`ApiServiceFactory` wires every API class against one base URL and exposes them
as two groupings — `AppActlApiService` and `AdminMgmtApiService`. Those names
mirror how the UI is organised, not two backends.

## Contract details worth knowing

Three things about this spec are load-bearing and are asserted by
`OpenApiSpecGeneratorTest`:

- **`snake_case` properties.** springdoc ignores Jackson's naming strategy unless
  a `ModelResolver` bound to the application `ObjectMapper` is registered.
- **Named enum schemas.** Enums are annotated `@Schema(enumAsRef = true)`;
  without it the generated client names a type after the property that uses it
  (`FamAuthGrantDtoAuthKeyEnum`) rather than `AdminRoleAuthGroup`.
- **Parameter order.** The generated client passes query parameters
  *positionally*, so the order of fields in a `@ParameterObject` class is part of
  the contract. `UserRolePageParams` declares `sortOrder` before `sortBy` to match
  what the frontend calls with.

`operationId` is set explicitly on every endpoint, preserving the names FastAPI
derived from its handler functions — those become the client's method names.

## References

- [OpenAPI Generator](https://openapi-generator.tech/docs/installation)
- [OpenAPI Generator CLI](https://www.npmjs.com/package/@openapitools/openapi-generator-cli)
