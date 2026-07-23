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
    // GraphQL codegen deliberately generates nothing for Product / ProductInput / ProductPage
    // (see typeMapping below) - it binds those GraphQL types straight to the classes
    // schema-openapi produces, so there is exactly one Java class per shape, used by both
    // contracts. Exposed via `api` so `service` gets both modules' types from one dependency.
    api(project(":schema-openapi"))
    compileOnly("jakarta.annotation:jakarta.annotation-api")
}

val graphqlSchemaDir = file("src/main/resources/graphql")
val schemaModelPackage = "com.zuhlke.catalog.schema.model"

// If a schema change makes one of the typeMapped types below impossible to bind as-is (e.g. a
// field schema-openapi can't represent the way GraphQL needs it), this task fails the build
// rather than silently generating a second, divergent type.
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
