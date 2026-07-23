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

// EXPERIMENT: feed openApiGenerate a pre-bundled single-file spec (see BundleOpenApiSpec in
// buildSrc) instead of the raw multi-file openapi.yaml, to test whether that avoids the
// Product1 duplicate-class issue (see ProductPage.yaml's `content.items` comment) - same-file
// "#/components/schemas/X" reuse is much better-tested in the generator than cross-file reuse.
// If the generated ProductPage.java's `content` field comes out as List<Product> (not
// List<Object> and not referencing a Product1), this worked and
// ProductCatalogService.toPageItem() can be deleted; if not, revert to inputSpec.set(openApiSpec...).
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
