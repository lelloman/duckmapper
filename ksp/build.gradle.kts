plugins {
    kotlin("jvm")
    `maven-publish`
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(project(":annotations"))
    implementation("com.google.devtools.ksp:symbol-processing-api:1.9.22-1.0.17")
    implementation("com.squareup:kotlinpoet:1.15.3")
    implementation("com.squareup:kotlinpoet-ksp:1.15.3")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "com.github.lelloman.duckmapper"
            artifactId = "ksp"
            version = project.version.toString()
            from(components["java"])
        }
    }
}
