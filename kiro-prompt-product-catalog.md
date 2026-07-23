# Prompt for kiro-cli: Product Catalog — Contract-First REST + GraphQL on One Shared Schema

Copy everything below this line into kiro-cli as the task prompt / context.

---

## Goal

Generate a complete, buildable Gradle multi-module project (Kotlin DSL) containing:

1. A contract-first REST API (OpenAPI 3.1) for a product catalog: CRUD + one search endpoint.
2. An equivalent GraphQL API exposing the exact same operations.
3. **REST and GraphQL must share the exact same generated Java types for every input and
   output shape (`Product`, `ProductInput`, `ProductPage`, `ErrorResponse`).** Not
   hand-written twin classes, not mapper/converter classes between two independently
   generated types — the literal same `.class`. If you find yourself writing a method that
   converts one generated type into a structurally-identical other generated type, that is a
   sign the code generation is misconfigured, not a normal step — stop and fix the
   generation, don't paper over it. Section 3 below explains the one real trap that causes
   this and exactly how to avoid it from the start.
4. Both a REST controller implementation and a GraphQL controller implementation, backed by
   one shared service/business-logic class (so there is exactly one implementation of the
   behavior, not two).
5. End-to-end tests for both transports, over real HTTP (`MockMvc` for REST,
   `HttpGraphQlTester` for GraphQL) — not unit tests against mocked layers.
6. Two Bruno API-client collections for manual testing: one for REST, one for GraphQL.

Read this entire document before writing any file. Section 3 describes a subtle code
generation bug that is very easy to hit and expensive to diagnose after the fact (it took
many iterations to root-cause in the project this prompt is derived from). Build the fix for
it as part of the initial scaffolding, not as a follow-up patch.

---

## 1. Toolchain — pin these exact versions

- **Spring Boot 4.1.0** (Java 21 baseline; note Spring Boot 4 renamed/modularized several
  starters — see Section 5).
- **Gradle 9.6.1**, Kotlin DSL (`build.gradle.kts` / `settings.gradle.kts`), Java 21 toolchain
  on every subproject.
- **`org.openapi.generator` Gradle plugin 7.14.0**, `generatorName = "spring"`.
- **`com.netflix.dgs.codegen` Gradle plugin 8.6.0** (Netflix DGS codegen, used only to
  validate/wire the GraphQL schema — see Section 4).
- **`io.spring.dependency-management` plugin 1.1.7** for BOM imports
  (`spring-boot-dependencies:4.1.0`).
- **`com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.2`** — used only inside
  `buildSrc`, for the custom bundling task in Section 3.

If newer stable versions of any of these are available at generation time and are a drop-in
replacement, using them is fine — the architecture and the workarounds below are what
matters, not the exact patch numbers.

---

## 2. Module layout

```
<root>/
  settings.gradle.kts          include("schema-openapi", "schema-graphql", "service")
  build.gradle.kts             root: java toolchain 21 for all subprojects, JUnit Platform
  buildSrc/                    custom build logic (the bundler task from Section 3)
    build.gradle.kts
    src/main/kotlin/BundleOpenApiSpec.kt
  schema-openapi/              OpenAPI Generator only. Produces the shared model/API classes.
    build.gradle.kts
    src/main/resources/openapi/
      openapi.yaml
      schemas/
        Product.yaml
        ProductInput.yaml
        ProductPage.yaml
        ErrorResponse.yaml
  schema-graphql/               DGS codegen only. Depends on schema-openapi.
    build.gradle.kts
    src/main/resources/graphql/
      schema.graphqls
  service/                      Spring Boot app: REST + GraphQL controllers, shared service.
    build.gradle.kts
    src/main/java/com/zuhlke/catalog/service/...
    src/test/java/com/zuhlke/catalog/service/...
  bruno/
    rest-api/...
    graphql-api/...
```

Do **not** collapse `schema-openapi` and `schema-graphql` into one module. Splitting them is
deliberate: `schema-openapi` owns the OpenAPI Generator run and is the single source of the
shared Java types; `schema-graphql` owns only the DGS codegen run and depends on
`schema-openapi` (`api(project(":schema-openapi"))`) so its `typeMapping` can bind GraphQL SDL
types straight onto `schema-openapi`'s already-generated classes instead of generating a
second set. `service` depends on both.

Base Java package for generated/shared types: `com.zuhlke.catalog.schema.model` (POJOs),
`com.zuhlke.catalog.schema.api` (generated REST interfaces). Service code lives under
`com.zuhlke.catalog.service`.

---

## 3. The one real trap: cross-file `$ref` reuse silently breaks type sharing

### The design that seems natural but doesn't work reliably

The natural way to write the OpenAPI contract is: each shape (`Product`, `ProductInput`, ...)
gets its own standalone JSON Schema fragment file under `schemas/`, so it can be a single
source of truth reused wherever it's needed — including *inside another fragment*. Concretely:
`ProductPage.yaml` has a `content` array whose `items` should just be "a Product", so it
`$ref`s `./Product.yaml` directly — the exact same file that `openapi.yaml`'s
`components/schemas/Product` entry also `$ref`s.

**This breaks.** OpenAPI Generator 7.14.0's dereferencer does not reliably recognize that
"`components/schemas/Product`, reached via `openapi.yaml`" and "the array items of
`ProductPage.content`, reached via a ref *inside* `ProductPage.yaml`" are the same model, even
though both ultimately point at the identical file. Concretely, two things were tried against
the raw multi-file spec and both failed, in different ways:

- **On OpenAPI 3.1 input**: it generates a second, structurally-identical class for the
  second usage (named `Product1`, since `Product` is taken). `ProductPage.content` ends up
  typed `List<Product1>`, not `List<Product>` — two classes for one shape, exactly what
  requirement 3 forbids.
- **On OpenAPI 3.0 input** (tried as a hypothesis that the 3.1/JSON-Schema-2020-12
  dereferencer specifically was the buggy one): it's worse. The 3.0 resolver doesn't chase a
  `$ref` found *inside* an already-externally-referenced fragment at all — it silently gives
  up and types the field as `Object`. `ProductPage.content` becomes `List<Object>`. This is a
  worse failure than the duplicate class: it's a silent loss of type information instead of a
  merely-cosmetic extra class.

Rewriting the `$ref` itself doesn't fix it either: a bare `#/components/schemas/Product`
pointer written *inside* `ProductPage.yaml` fails outright (a fragment-local `#/...` pointer
only resolves within that same file, per JSON Reference semantics — it can't reach back into
`openapi.yaml`'s `components/schemas`). An explicit cross-file pointer
(`../openapi.yaml#/components/schemas/Product`) resolves without erroring, but still produces
the duplicate-class result, not deduplication.

### The fix: bundle before generating, don't hand-tune refs

The pattern OpenAPI Generator *does* handle correctly and is heavily tested is: multiple
places in **one single-file, fully-resolved document** all pointing at the same literal
string `#/components/schemas/Product`. So: don't feed OpenAPI Generator the hand-authored
multi-file spec directly. Add a Gradle task that runs first and merges the multi-file spec
into one single-file JSON document, where every reference to a given fragment file —
regardless of which file it was originally written in — is rewritten to that same literal
`#/components/schemas/<Name>` pointer. Feed *that* bundled file to OpenAPI Generator.

Implement this as a custom Gradle task in `buildSrc` (not inline Groovy/Kotlin string
manipulation in the build script, and not an external Node-based tool like `redocly`/
`swagger-cli`, which would require Node.js to be installed on every machine that builds this
project — a plain JVM implementation using Jackson, which Gradle/the JVM toolchain already
guarantees is available, has no such external dependency).

Create `buildSrc/build.gradle.kts`:

```kotlin
// buildSrc holds custom build logic (BundleOpenApiSpec.kt) used by schema-openapi/build.gradle.kts.
// Anything declared as a dependency here is automatically on the classpath of every build script
// in the main build - that's how schema-openapi/build.gradle.kts can reference BundleOpenApiSpec
// without any explicit buildscript{}/classpath wiring there.
plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.2")
}
```

Create `buildSrc/src/main/kotlin/BundleOpenApiSpec.kt`:

```kotlin
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Bundles the multi-file OpenAPI spec (openapi.yaml + the schemas directory's fragment files)
 * into a single JSON document where every reference to a shared fragment - whether declared as
 * a top-level named component (components/schemas/<Name>) or reached indirectly through another
 * fragment (e.g. ProductPage.yaml's `content.items`) - is canonicalized to the exact same
 * "#/components/schemas/<Name>" pointer.
 *
 * Why this exists: OpenAPI Generator's dereferencer does not reliably recognize that two
 * *different* reference paths reaching the *same* fragment file describe the *same* model. On
 * OpenAPI 3.1 input it silently duplicates the class ("Product1"); on 3.0 input it gives up
 * entirely and types the field as Object (both tried directly against the multi-file spec).
 * Feeding the generator a single, already-bundled file sidesteps this: same-file, same-string
 * "#/components/schemas/X" reuse is the well-tested, primary code path in the tool.
 *
 * Deliberately fails the build (rather than falling back to something silently wrong) if a
 * relative $ref is found that doesn't resolve to one of the fragments declared under
 * components/schemas - fail fast, don't silently regenerate a duplicate or untyped model.
 */
abstract class BundleOpenApiSpec : DefaultTask() {

    @get:InputFile
    abstract val inputSpec: RegularFileProperty

    @get:OutputFile
    abstract val outputSpec: RegularFileProperty

    @TaskAction
    fun bundle() {
        val yamlMapper = YAMLMapper()
        val jsonMapper = ObjectMapper()
        val mapType = object : TypeReference<LinkedHashMap<String, Any?>>() {}

        val rootFile = inputSpec.get().asFile
        val rootDir = rootFile.parentFile
        val root: LinkedHashMap<String, Any?> = yamlMapper.readValue(rootFile, mapType)

        @Suppress("UNCHECKED_CAST")
        val components = root["components"] as? LinkedHashMap<String, Any?>
            ?: throw GradleException("$rootFile has no components section to bundle")
        @Suppress("UNCHECKED_CAST")
        val schemas = components["schemas"] as? LinkedHashMap<String, Any?>
            ?: throw GradleException("$rootFile has no components/schemas section to bundle")

        // Pass 1: every top-level components/schemas/<Name> entry is expected to be a bare
        // {$ref: <relative file>} stub. Resolve which file each one points at and remember the
        // mapping canonical-file-path -> component name *before* inlining anything, so later
        // passes can recognize "this is the same fragment" regardless of how it's reached.
        val canonicalPathToName = LinkedHashMap<String, String>()
        val fragmentRawContent = LinkedHashMap<String, LinkedHashMap<String, Any?>>()
        val fragmentDirs = LinkedHashMap<String, File>()
        for ((name, value) in schemas) {
            val refPath = singleRefTarget(value)
                ?: throw GradleException(
                    "components/schemas/$name in $rootFile must be a bare {\$ref: <file>} stub " +
                        "pointing at a fragment file - found something else instead."
                )
            val fragmentFile = File(rootDir, refPath).canonicalFile
            canonicalPathToName[fragmentFile.path] = name
            fragmentRawContent[name] = yamlMapper.readValue(fragmentFile, mapType)
            fragmentDirs[name] = fragmentFile.parentFile
        }

        // Pass 2: bundle each fragment's own content - rewriting any nested relative $refs it
        // contains (e.g. ProductPage.yaml's `content.items`), relative to that fragment's own
        // directory - and inline the result directly into components/schemas/<Name>, replacing
        // the {$ref: ...} stub with the real schema body.
        for ((name, rawContent) in fragmentRawContent) {
            schemas[name] = rewriteRefs(rawContent, fragmentDirs.getValue(name), canonicalPathToName)
        }

        // Pass 3: safety net - rewrite any remaining relative $refs anywhere else in the
        // document (paths, parameters, responses, ...). None should exist if operations already
        // use "#/components/schemas/<Name>" directly, but this keeps the bundler correct - and
        // loud, not silent - if that ever changes.
        @Suppress("UNCHECKED_CAST")
        val bundledRoot = rewriteRefs(root, rootDir, canonicalPathToName) as LinkedHashMap<String, Any?>

        val outFile = outputSpec.get().asFile
        outFile.parentFile.mkdirs()
        jsonMapper.writerWithDefaultPrettyPrinter().writeValue(outFile, bundledRoot)
    }

    private fun singleRefTarget(node: Any?): String? {
        val map = node as? Map<*, *> ?: return null
        if (map.size != 1) return null
        return map["\$ref"] as? String
    }

    private fun rewriteRefs(
        node: Any?,
        baseDir: File,
        canonicalPathToName: Map<String, String>
    ): Any? = when (node) {
        is Map<*, *> -> {
            val refValue = node["\$ref"] as? String
            if (node.size == 1 && refValue != null && !refValue.startsWith("#")) {
                val canonicalPath = File(baseDir, refValue).canonicalFile.path
                val name = canonicalPathToName[canonicalPath]
                    ?: throw GradleException(
                        "Found \$ref \"$refValue\" (resolved to $canonicalPath) that doesn't " +
                            "match any fragment declared under components/schemas. Every " +
                            "fragment used anywhere in the spec must also be declared there so " +
                            "the bundler can dedupe it - add it instead of letting the build " +
                            "silently regenerate a duplicate or untyped model."
                    )
                linkedMapOf<String, Any?>("\$ref" to "#/components/schemas/$name")
            } else {
                val result = LinkedHashMap<String, Any?>()
                for ((key, value) in node) {
                    result[key as String] = rewriteRefs(value, baseDir, canonicalPathToName)
                }
                result
            }
        }
        is List<*> -> node.map { rewriteRefs(it, baseDir, canonicalPathToName) }
        else -> node
    }
}
```

**Watch out for this specific Kotlin syntax trap when writing the KDoc comment above the
class**: Kotlin block comments nest (unlike Java's). If the comment text contains the literal
two-character sequence `/*` anywhere — e.g. writing `schemas/*.yaml` to mean "the `.yaml`
files under `schemas/`" — Kotlin treats that as *opening a second, nested* comment, which
then needs its own closing `*/`. Without one, the whole rest of the file is swallowed as a
comment and you get a confusing "Unclosed comment" error pointing at the *last* line of the
file, not the line with the actual mistake. Avoid writing `/*` inside any comment in this
file (the version above already avoids it — don't reintroduce it while editing).

Then in `schema-openapi/build.gradle.kts`, register the task and point `openApiGenerate` at
its output instead of the raw spec:

```kotlin
val bundledOpenApiSpec = layout.buildDirectory.file("generated/openapi-bundled/openapi.json")

val bundleOpenApiSpec by tasks.registering(BundleOpenApiSpec::class) {
    inputSpec.set(openApiSpec)
    outputSpec.set(bundledOpenApiSpec)
    outputs.upToDateWhen { false }
}

openApiGenerate {
    generatorName.set("spring")
    inputSpec.set(bundledOpenApiSpec.get().asFile.toURI().toString())
    // ...rest of config, see Section 6...
}

tasks.named("openApiGenerate") {
    dependsOn(bundleOpenApiSpec)
}
```

`inputSpec.set(bundledOpenApiSpec.get().asFile.toURI().toString())` extracts the path eagerly
at configuration time — that's fine here (it's a fixed path under the build directory), but it
means Gradle won't auto-infer the task ordering from that alone, hence the explicit
`dependsOn(bundleOpenApiSpec)` above.

**Verification for this section**: after generation, `schema-openapi/build/generated/openapi/
.../model/ProductPage.java` must declare `private List<Product> content` (or
`List<@Valid Product>`) — not `List<Object>`, and there must be no `Product1.java` anywhere
under `schema-openapi/build/generated/`. If either of those is wrong, the bundler isn't wired
in correctly (check `dependsOn`, check that `inputSpec` really points at the bundled file, not
the original `openapi.yaml`).

---

## 4. `schema-graphql`: bind GraphQL types onto the same classes, don't regenerate them

`schema-graphql/build.gradle.kts` depends on `schema-openapi` and configures DGS codegen's
`typeMapping` so `Product`, `ProductInput`, `ProductPage` in the GraphQL SDL bind directly to
the classes `schema-openapi` already produced, instead of generating a second set:

```kotlin
plugins {
    `java-library`
    id("io.spring.dependency-management") version "1.1.7"
    id("com.netflix.dgs.codegen") version "8.6.0"
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:4.1.0")
    }
}

dependencies {
    // Exposed via `api` so `service` gets both modules' types from one dependency.
    api(project(":schema-openapi"))
    compileOnly("jakarta.annotation:jakarta.annotation-api")
}

val graphqlSchemaDir = file("src/main/resources/graphql")
val schemaModelPackage = "com.zuhlke.catalog.schema.model"

// If a schema change makes one of the typeMapped types below impossible to bind as-is, this
// task fails the build rather than silently generating a second, divergent type.
tasks.generateJava {
    schemaPaths.add(graphqlSchemaDir.path)
    packageName = "com.zuhlke.catalog.schema.graphql"
    generateDataTypes = true
    generateClient = false
    typeMapping = mutableMapOf(
        "Product" to "$schemaModelPackage.Product",
        "ProductInput" to "$schemaModelPackage.ProductInput",
        "ProductPage" to "$schemaModelPackage.ProductPage"
    )
}

tasks.named("compileJava") {
    dependsOn("generateJava")
}

tasks.named("processResources") {
    dependsOn("generateJava")
}
```

**DGS codegen DSL gotcha**: the task must be referenced as `tasks.generateJava { ... }` (the
`tasks.` prefix matters — a bare `generateJava { }` block is an unresolved reference). Inside
that block, DGS's own task properties (`schemaPaths`, `packageName`, `generateDataTypes`,
`generateClient`, `typeMapping`) are plain Kotlin properties assigned with `=`, **not**
Gradle `Property<T>`-style `.set(...)` calls the way OpenAPI Generator's DSL works — mixing
the two conventions up (`.set(...)` here) produces an unresolved-reference error.

`schema.graphqls` mirrors `openapi.yaml`'s operations one-for-one (`listProducts` →
`products`, `createProduct` → `createProduct`, `searchProducts` → `searchProducts`,
`getProductById` → `product`, `updateProduct` → `updateProduct`, `deleteProduct` →
`deleteProduct`), and its `Product` / `ProductInput` / `ProductPage` type definitions mirror
the corresponding JSON Schema fragment field-for-field (they exist only so the SDL is
self-describing and so DGS codegen has something to validate `typeMapping` against — no Java
is generated from them, `typeMapping` redirects that entirely).

---

## 5. `service` module: Spring Boot 4 starter renames and other runtime specifics

Spring Boot 4 modularized several starters and moved several test-autoconfiguration
annotations to new packages/artifacts compared to Spring Boot 3. Get these right the first
time — don't assume Spring Boot 3 coordinates still work:

```kotlin
plugins {
    java
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
}

dependencies {
    implementation(project(":schema-openapi"))
    implementation(project(":schema-graphql"))

    // Spring Boot 4 renamed this from spring-boot-starter-web to make the MVC-vs-WebFlux
    // choice explicit.
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-graphql")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Spring Boot 4 also modularized test auto-configuration per-feature: the annotations
    // that used to come bundled in spring-boot-starter-test now need their own starters.
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-graphql-test")
}
```

And the matching test annotation imports (these moved packages, they did **not** stay at
their Spring Boot 3 locations):

```java
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
```
```java
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureHttpGraphQlTester;
```

### REST controller: the generated interface has no base path — add it yourself

OpenAPI Generator's "spring" generator with `interfaceOnly=true` (see Section 6 for the full
config) produces a `ProductsApi` interface whose method-level `@RequestMapping`s are rooted at
paths like `/products`, `/products/{productId}`, `/products/search` directly — **it does not
add a class-level `/api` prefix derived from `openapi.yaml`'s `servers: - url: /api` entry.**
If you set that `servers` entry and then expect the base path to show up automatically in the
generated routes, it won't — the controller implementing `ProductsApi` must add
`@RequestMapping("/api")` at the class level itself; Spring MVC composes it with each
inherited method-level mapping:

```java
@RestController
@RequestMapping("/api")
public class ProductRestController implements ProductsApi {
    // ...
}
```

Do **not** instead set a global `server.servlet.context-path: /api` in
`application.properties` — that would also prefix `/graphql`, `/graphiql`, and any
`/openapi/**` static-resource mapping you add, which should stay unprefixed.

### Shared service layer

One `@Service` class (`ProductCatalogService`) implements `create`/`get`/`update`/`delete`/
`list`/`search`, operating purely on the shared `Product`/`ProductInput`/`ProductPage` types.
Both the REST controller and the GraphQL controller call it directly — neither transport has
its own copy of the business logic, and neither needs any type-bridging/conversion code (once
Section 3's bundler is in place, `ProductPage.setContent(...)` takes a plain `List<Product>`
directly).

Package layout under `com.zuhlke.catalog.service`:
- `ProductCatalogService` — the shared logic (backed by a simple in-memory
  `ConcurrentHashMap`-based `repository.ProductRepository` is sufficient for this scope).
- `domain.ProductNotFoundException` — thrown by the service, translated by both transports.
- `rest.ProductRestController` — implements the generated `ProductsApi`, delegates to the
  service, translates GraphQL/REST-agnostic exceptions.
- `rest.RestExceptionHandler` (`@RestControllerAdvice`) — maps `ProductNotFoundException` →
  404, `MethodArgumentNotValidException` → 400, `IllegalArgumentException` → 400, all using
  the shared `ErrorResponse` type (also generated from a fragment, so REST error bodies stay
  contract-shaped).
- `graphql.ProductGraphQlController` (`@Controller`, `@QueryMapping`/`@MutationMapping`) —
  same service, same shared types; GraphQL's `ID` scalar (transport-level `String`) is
  converted to `UUID` at the edge here, matching what Spring MVC's path-variable conversion
  does for REST.
- `graphql.GraphQlExceptionResolver` (extends `DataFetcherExceptionResolverAdapter`) — mirrors
  `RestExceptionHandler`'s behavior for the GraphQL transport (`ProductNotFoundException` →
  `ErrorType.NOT_FOUND`, `IllegalArgumentException` → `ErrorType.BAD_REQUEST`).
- `config.WebConfig` (`WebMvcConfigurer`) — serves the actual `openapi.yaml` contract (and the
  fragments it `$ref`s) as static files straight from `schema-openapi`'s classpath resources,
  reachable at `/openapi/openapi.yaml`, `/openapi/schemas/Product.yaml`, etc. — so the file you
  edit is the file clients fetch, no separate "publish the spec" step:
  ```java
  @Configuration
  public class WebConfig implements WebMvcConfigurer {
      @Override
      public void addResourceHandlers(ResourceHandlerRegistry registry) {
          registry.addResourceHandler("/openapi/**").addResourceLocations("classpath:/openapi/");
      }
  }
  ```

---

## 6. `schema-openapi`: full OpenAPI Generator configuration

```kotlin
plugins {
    `java-library`
    id("io.spring.dependency-management") version "1.1.7"
    id("org.openapi.generator") version "7.14.0"
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:4.1.0")
    }
}

dependencies {
    // Needed by the model/API classes OpenAPI Generator emits (ResponseEntity, @RequestMapping,
    // @Validated, @DateTimeFormat, ...).
    api("org.springframework:spring-web")
    api("org.springframework:spring-context")
    api("jakarta.validation:jakarta.validation-api")
    api("com.fasterxml.jackson.core:jackson-annotations")
    compileOnly("jakarta.annotation:jakarta.annotation-api")
}

val openApiSpec = file("src/main/resources/openapi/openapi.yaml")
val generatedOpenApiDir = layout.buildDirectory.dir("generated/openapi").get().asFile
val schemaModelPackage = "com.zuhlke.catalog.schema.model"
val schemaApiPackage = "com.zuhlke.catalog.schema.api"

// See Section 3 for BundleOpenApiSpec.
val bundledOpenApiSpec = layout.buildDirectory.file("generated/openapi-bundled/openapi.json")

val bundleOpenApiSpec by tasks.registering(BundleOpenApiSpec::class) {
    inputSpec.set(openApiSpec)
    outputSpec.set(bundledOpenApiSpec)
    outputs.upToDateWhen { false }
}

openApiGenerate {
    generatorName.set("spring")
    inputSpec.set(bundledOpenApiSpec.get().asFile.toURI().toString())
    outputDir.set(generatedOpenApiDir.path)
    modelPackage.set(schemaModelPackage)
    apiPackage.set(schemaApiPackage)
    invokerPackage.set("com.zuhlke.catalog.schema.invoker")
    // Interfaces only - the `service` module supplies the @RestController implementation.
    // This module's job is strictly "generate the shared types", not runtime wiring.
    library.set("spring-boot")
    // NOTE on these values - openapi-generator's global properties are booby-trapped:
    //   - Omitting a key entirely defaults it to OFF as soon as ANY other key is present
    //     (the "generate everything" default only applies when the map is fully empty).
    //   - A literal "true" turns the category on, but is ALSO read as a comma-separated
    //     whitelist of specific model/api names - so "true" silently matches nothing and
    //     generates zero classes.
    // An empty string is the only value that means "on, and don't filter by name".
    globalProperties.set(
        mapOf(
            "apis" to "",
            "models" to "",
            "supportingFiles" to "false",
            "modelDocs" to "false",
            "apiDocs" to "false",
            "modelTests" to "false",
            "apiTests" to "false"
        )
    )
    configOptions.set(
        mapOf(
            "useSpringBoot3" to "true",
            "interfaceOnly" to "true",
            "useTags" to "true",
            "skipDefaultInterface" to "true",
            "useBeanValidation" to "true",
            "openApiNullable" to "false",
            "documentationProvider" to "none",
            "annotationLibrary" to "none",
            "dateLibrary" to "java8",
            "hideGenerationTimestamp" to "true",
            "serializableModel" to "true"
        )
    )
}

tasks.withType<org.openapitools.generator.gradle.plugin.tasks.GenerateTask> {
    // Force re-generation on every build instead of relying on the plugin's own (sometimes
    // stale) up-to-date check while schema fragments are still being iterated on.
    outputs.upToDateWhen { false }
}

tasks.named("openApiGenerate") {
    dependsOn(bundleOpenApiSpec)
}

sourceSets {
    main {
        java {
            srcDir(generatedOpenApiDir.resolve("src/main/java"))
        }
    }
}

tasks.named("compileJava") {
    dependsOn("openApiGenerate")
}
```

**Two more Kotlin DSL gotchas to avoid while writing this file:**

- **Windows path / URI**: `inputSpec` (and any other file-path property on this plugin) must
  be set from `someFile.toURI().toString()`, not a raw Windows path string
  (`C:\...\openapi.yaml`) and not `someFile.path` alone. A bare Windows path is not a valid
  URI (backslashes, drive-letter colon) and throws `URISyntaxException`; `.toURI().toString()`
  produces a proper `file:///C:/...` URI that the plugin accepts on any OS.
- **Don't shadow the DSL setters with same-named local `val`s.** If you write
  `val modelPackage = "..."` and then also call `modelPackage.set(...)` inside the
  `openApiGenerate { }` block, Kotlin resolves the reference to your own local `val`, not the
  plugin's property, causing a circular/self-referential evaluation error at configuration
  time. Name locals distinctly (`schemaModelPackage`, `schemaApiPackage`, as above).

### `openapi.yaml` authoring rules

- `openapi: 3.1.0` (see Section 3 for why 3.0.3 is not actually better despite seeming like a
  natural "beta workaround" — don't downgrade it).
- `servers: - url: /api` documents the intended base path for humans/tooling reading the
  spec, but **does not** get baked into the generated Spring routes automatically — the
  `service` module's controller adds `@RequestMapping("/api")` itself (Section 5).
- Every reusable shape gets a `components/schemas/<Name>` entry that itself is *only* a
  `$ref` to a standalone fragment file under `schemas/<Name>.yaml` — never inline a schema
  body directly in `openapi.yaml`. This indirection is what makes OpenAPI Generator treat it
  as a reusable named model instead of a fresh synthetic type per operation; the fragment file
  remains the actual source of truth for the shape.
- All operations reference schemas via `#/components/schemas/<Name>`, never via a direct
  fragment file path (`./schemas/Product.yaml`) — only the `components/schemas` declarations
  themselves point at fragment files.
- Give each fragment a `title:` matching its intended class name (`title: Product`, etc.) as
  an extra naming safety net.
- Endpoints: `GET /products` (list, paged), `POST /products` (create), `GET /products/search`
  (the one search endpoint — scalar filter params: `name`, `category`, `minPrice`, `maxPrice`,
  plus paging, so the same filters can be expressed identically as GraphQL field arguments),
  `GET /products/{productId}`, `PUT /products/{productId}`, `DELETE /products/{productId}`.
  404 responses use the shared `ErrorResponse` schema via a reusable
  `components/responses/NotFound`; 400s via `components/responses/BadRequest`.

### Fragment files (`schemas/*.yaml`)

- `Product.yaml`: `type: object`, `title: Product`, required `id`, `name`, `price`,
  `quantity`; `id`/`createdAt`/`updatedAt` are `readOnly: true` (server-assigned);
  `description`/`category` are `nullable: true`.
- `ProductInput.yaml`: same writable fields as `Product` minus `id`/timestamps — the
  create/update request body and the GraphQL `ProductInput` input type.
- `ProductPage.yaml`: `content` (array, `items: $ref: "./Product.yaml"` — this is exactly the
  cross-file reuse Section 3's bundler exists to handle correctly), `page`, `size`,
  `totalElements`, `totalPages`.
- `ErrorResponse.yaml`: `status`, `error`, `message`, `path`, `timestamp` — RFC-7807-flavored,
  simplified. Used for every non-2xx REST response.

---

## 7. Tests

Both test classes hit the real HTTP layer with a full Spring context — not mocked service
layers:

- `RestApiE2ETest` (`@SpringBootTest`, `@AutoConfigureMockMvc`): create → read → update →
  search (match) → delete → read (404), plus a separate test asserting a no-match search
  returns an empty page with 200, not an error.
- `GraphQlE2ETest` (`@SpringBootTest(webEnvironment = RANDOM_PORT)`,
  `@AutoConfigureHttpGraphQlTester`): the same create/read/update/search/delete flow as GraphQL
  operations over real HTTP, plus a test asserting deleting an unknown id surfaces a GraphQL
  error containing that id.

Both should assert on the exact shared-type field values (e.g. `$.name` /
`.path("product.name")`) to prove both transports are backed by the same behavior and data.

---

## 8. Bruno collections

Two separate collections, each with a `bruno.json`, an `environments/Local.bru` defining
`baseUrl: http://localhost:8080`, and one `.bru` request per operation:

**`bruno/rest-api/`** (`type: collection`, name e.g. "Product Catalog - REST (OpenAPI)"):
List Products, Create Product, Search Products, Get Product, Update Product, Delete Product,
plus a "Get OpenAPI Spec" request against `/openapi/openapi.yaml`. Each REST request has
`meta { type: http }`; the Create/Update requests set `body: json` with a realistic payload;
`Create Product`'s `script:post-response` captures the created id into `bru.setVar("productId",
res.body.id)` so later requests in the collection can reference `{{productId}}`; each request
has an `assert` block checking the expected status code.

**`bruno/graphql-api/`**: same six product operations, each `meta { type: graphql }`,
`url: {{baseUrl}}/graphql`, `body: graphql` containing the query/mutation document plus a
`body:graphql:vars` block for variables. `Create Product`'s `script:post-response` captures
`res.body.data.createProduct.id` into `productId` the same way.

Example REST request (`bruno/rest-api/Create Product.bru`):

```
meta {
  name: Create Product
  type: http
  seq: 1
}

post {
  url: {{baseUrl}}/api/products
  body: json
  auth: none
}

headers {
  Content-Type: application/json
}

body:json {
  {
    "name": "Wireless Mouse",
    "description": "Ergonomic wireless mouse",
    "price": 29.99,
    "quantity": 100,
    "category": "Accessories"
  }
}

script:post-response {
  if (res.body && res.body.id) {
    bru.setVar("productId", res.body.id);
  }
}

assert {
  res.status: eq 201
}
```

Example GraphQL request (`bruno/graphql-api/Create Product.bru`):

```
meta {
  name: Create Product
  type: graphql
  seq: 1
}

post {
  url: {{baseUrl}}/graphql
  body: graphql
  auth: none
}

headers {
  Content-Type: application/json
}

body:graphql {
  mutation CreateProduct($input: ProductInput!) {
    createProduct(input: $input) {
      id
      name
      price
      quantity
      category
    }
  }
}

body:graphql:vars {
  {
    "input": {
      "name": "Mechanical Keyboard",
      "description": "Hot-swappable mechanical keyboard",
      "price": 89.5,
      "quantity": 40,
      "category": "Accessories"
    }
  }
}

script:post-response {
  if (res.body && res.body.data && res.body.data.createProduct) {
    bru.setVar("productId", res.body.data.createProduct.id);
  }
}

assert {
  res.status: eq 200
}
```

---

## 9. Root project files

`settings.gradle.kts`:

```kotlin
rootProject.name = "restapi-and-graphql"

include("schema-openapi")
include("schema-graphql")
include("service")
```

(`buildSrc/` needs no entry here — Gradle auto-detects it by convention.)

Root `build.gradle.kts`:

```kotlin
plugins {
    java
}

allprojects {
    group = "com.zuhlke.catalog"
    version = "0.1.0"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
```

Generate the Gradle wrapper for 9.6.1 (`gradle wrapper --gradle-version 9.6.1`) if you have
network access to do so; otherwise leave `gradle/wrapper/gradle-wrapper.properties` pointing
at `distributionUrl=https\://services.gradle.org/distributions/gradle-9.6.1-bin.zip` and note
in the README that the wrapper jar itself still needs to be generated by whoever has Maven
Central / Gradle Plugin Portal access, if generation happens in an offline sandbox.

---

## 10. Definition of done — self-check before declaring this finished

Run `./gradlew build` (or equivalent) and confirm, in order:

1. `schema-openapi:bundleOpenApiSpec` runs before `schema-openapi:openApiGenerate` and
   produces `schema-openapi/build/generated/openapi-bundled/openapi.json`.
2. `schema-openapi/build/generated/openapi/.../model/ProductPage.java` declares
   `content` as `List<Product>` (or `List<@Valid Product>`) — **no** `Product1.java` exists
   anywhere under `schema-openapi/build/generated/`.
3. `schema-graphql:generateJava` succeeds without generating its own `Product`/
   `ProductInput`/`ProductPage` classes (only `DgsConstants`/similar plumbing, if anything).
4. `service:compileJava` succeeds with **zero** hand-written conversion methods between any
   two representations of `Product`, `ProductInput`, or `ProductPage`.
5. All REST E2E tests pass against `/api/products...` (not `/products...`).
6. All GraphQL E2E tests pass against `/graphql`.
7. Both Bruno collections' requests are internally consistent with the running app's actual
   routes (`/api/products...` for REST, `/graphql` for GraphQL).

If step 2 or 4 fails, the bundler from Section 3 isn't wired in correctly — re-check
`dependsOn(bundleOpenApiSpec)` and that `openApiGenerate.inputSpec` points at
`bundledOpenApiSpec`, not the raw `openapi.yaml`, before doing anything else.
