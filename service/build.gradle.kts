plugins {
    java
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
}

dependencies {
    // schema-graphql exposes schema-openapi transitively (via `api`), but both are listed
    // explicitly here so the dependency on the shared model/API types doesn't rely on that
    // being true forever.
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
