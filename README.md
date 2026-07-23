# RestApiAndGraphQL - Product Catalog

Contract-first REST + GraphQL over one product catalog, built with Spring Boot 4.1.0 / Java 21 / Gradle.

## Why this exists (and the schema-sharing design)

OpenAPI (JSON Schema) and GraphQL (SDL) are different type systems - no single tool
generates identical Java classes from both directly. This project reconciles that as follows,
so REST and GraphQL still end up using the **exact same generated Java classes** for
`Product`, `ProductInput` and `ProductPage`:

1. `schema/src/main/resources/openapi/schemas/*.yaml` - standalone JSON Schema fragments.
   These are the single source of truth for each shape.
2. `schema/src/main/resources/openapi/openapi.yaml` - the REST contract, `$ref`s those
   fragments (never inlines a schema). Genuinely contract-first.
3. OpenAPI Generator (Gradle plugin, configured in `schema/build.gradle.kts`) generates the
   Java model POJOs + `ProductsApi` interface from `openapi.yaml` into
   `com.zuhlke.catalog.schema.model` / `.api`.
4. `schema/src/main/resources/graphql/schema.graphqls` - the GraphQL contract, hand-authored
   to mirror the same operations and fields field-for-field.
5. The Netflix DGS codegen Gradle plugin (also configured in `schema/build.gradle.kts`) is
   given a `typeMapping` for `Product`, `ProductInput` and `ProductPage` pointing straight at
   the classes step 3 already generated. It does **not** generate its own classes for these
   types - it binds to the existing ones. If a future schema change makes a type impossible
   to bind this way, that codegen task fails the build rather than silently forking the type.

Net effect: one generated class per shape, imported by both transports. The `service` module
never defines its own DTOs for these - it depends on `schema` and uses those classes directly,
in the repository, the shared `ProductCatalogService`, the REST controller and the GraphQL
resolvers alike.

## Modules

- `schema/` - the JSON Schema fragments, `openapi.yaml`, `schema.graphqls`, and the two
  codegen plugins that turn them into Java types. No hand-written business logic lives here.
- `service/` - the Spring Boot application. Implements `ProductsApi` for REST
  (`rest/ProductRestController`) and GraphQL resolvers (`graphql/ProductGraphQlController`),
  both backed by one in-memory `ProductRepository` + `ProductCatalogService`.

## Endpoints

REST (base path `/api`, per `openapi.yaml`'s `servers`):

| Method | Path                    | Purpose      |
|--------|-------------------------|--------------|
| POST   | `/products`             | Create       |
| GET    | `/products/{productId}` | Read         |
| PUT    | `/products/{productId}` | Update       |
| DELETE | `/products/{productId}` | Delete       |
| GET    | `/products`             | List (paged) |
| GET    | `/products/search`      | Search       |

The raw contract is served as-is at `GET /openapi/openapi.yaml` (and its fragments under
`/openapi/schemas/...`), so the file you edit is the file clients fetch.

GraphQL (`POST /graphql`, GraphiQL UI at `/graphiql` when running):

`Query.product`, `Query.products`, `Query.searchProducts`, `Mutation.createProduct`,
`Mutation.updateProduct`, `Mutation.deleteProduct` - one-to-one with the REST operations above.

## Building and running

This project was authored in a sandbox with no internet access to Maven Central / the Gradle
Plugin Portal and only a JDK 11 runtime, so **the build has not been executed or compiled
here** - only statically reviewed (YAML fragments validated, package/type names
cross-checked by hand). On a machine with normal internet access and JDK 17+:

```bash
# Generates gradlew/gradlew.bat/gradle-wrapper.jar for this project. Requires a local
# Gradle install once; after that, use ./gradlew for everything.
gradle wrapper --gradle-version 9.6.1

./gradlew build          # compiles both modules, runs all tests
./gradlew :service:bootRun
```

If `ProductRestController`'s `@Override` methods don't match the generated `ProductsApi`
after your first build, that's the compiler telling you the actual openapi-generator output
differs slightly from the assumed interface shape documented in that class's Javadoc - adjust
the method signatures to match.

## Tests

- `service/src/test/java/.../RestApiE2ETest.java` - full REST CRUD + search over MockMvc.
- `service/src/test/java/.../GraphQlE2ETest.java` - the same flow over real HTTP GraphQL
  requests (`HttpGraphQlTester`, random port).

## Bruno collections

Two collections under `bruno/`, each with a `Local` environment (`baseUrl` defaults to
`http://localhost:8080`):

- `bruno/rest-api/` - CRUD + search + fetching the raw OpenAPI spec.
- `bruno/graphql-api/` - the equivalent queries/mutations.

Open either folder as a collection in Bruno. Run "Create Product" first in either collection -
it captures the new id into a `productId` variable that the other requests reuse.
