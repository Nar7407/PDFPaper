plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
}

dependencies {
    // T49: static analysis only. The PDF model comes from our own engine; no
    // third-party PDF, malware or network library is used.
    implementation(project(":engine"))
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.24")
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test> {
    useJUnit()
}
