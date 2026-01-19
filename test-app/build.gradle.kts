plugins {
    kotlin("jvm")
    id("com.google.devtools.ksp")
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(project(":annotations"))
    implementation(project(":test-domain"))
    implementation(project(":test-ui"))
    ksp(project(":ksp"))

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
}

tasks.test {
    useJUnitPlatform()
}
