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
