plugins {
    kotlin("jvm")
    `maven-publish`
}

dependencies {
    implementation(kotlin("stdlib"))
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "com.github.lelloman.duckmapper"
            artifactId = "annotations"
            version = project.version.toString()
            from(components["java"])
        }
    }
}
