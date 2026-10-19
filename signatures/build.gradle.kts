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
    // Signature *validation* only: the PDF structure comes from our engine and
    // the cryptography from the JDK (JCA + CertificateFactory). No third-party
    // PDF or crypto library, and no BouncyCastle.
    implementation(project(":engine"))
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.24")
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test> {
    useJUnit()
}

// T24: signature CLI. Usage:
//   ./gradlew :signatures:verify --args="signatures verify in.pdf"
tasks.register<JavaExec>("verify") {
    group = "verification"
    description = "Verify the digital signatures in a PDF against a trust store."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.pdfpaper.signatures.tools.SignaturesCliKt")
    // Paths on the command line are resolved from the repo root, not signatures/.
    workingDir = rootProject.projectDir
}
