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
    // The JVM host side of the compressor: a JPEG codec for the images the
    // engine re-encodes, and the `compress` CLI. The PDF work itself stays in
    // :engine; this module only supplies the host codec the engine asks for.
    implementation(project(":engine"))
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.24")
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test> {
    useJUnit()
}

// CLI: ./gradlew :render-jvm:compress --args="compress in.pdf --preset balanced --out out.pdf"
// (or via the repo-root `cli` wrapper: cli compress ...). Paths are resolved
// from the repo root, not render-jvm/.
tasks.register<JavaExec>("compress") {
    group = "verification"
    description = "Compress a PDF (compress in.pdf --preset balanced --out out.pdf)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.pdfpaper.renderjvm.tools.PdfCompressCliKt")
    workingDir = rootProject.projectDir
}

// T45: page export and embedded image extraction. Usage:
//   ./gradlew :render-jvm:export --args="export-images in.pdf --out-dir out --dpi 150"
tasks.register<JavaExec>("export") {
    group = "verification"
    description = "Export pages as images or extract embedded images (export-images/extract-images)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.pdfpaper.renderjvm.tools.PdfExportCliKt")
    workingDir = rootProject.projectDir
}
