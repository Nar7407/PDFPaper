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
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.24")
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test> {
    useJUnit()
}

val testdataDir = rootProject.file("testdata")
val corpusReport = layout.buildDirectory.file("corpus-report.txt")

tasks.register<JavaExec>("corpusCheck") {
    group = "verification"
    description = "Opens every PDF in /testdata with the engine and writes build/corpus-report.txt."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.pdfpaper.engine.tools.CorpusCheckKt")
    args(testdataDir.absolutePath, corpusReport.get().asFile.absolutePath)
    inputs.dir(testdataDir)
    outputs.file(corpusReport)
}

// T16: font diagnostics used while debugging extraction warnings.
// Usage: cli fontinfo file.pdf --page 6  (or ./gradlew :engine:fontinfo --args="...")
tasks.register<JavaExec>("fontinfo") {
    group = "verification"
    description = "Prints font dictionaries and degradation reasons for one page."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.pdfpaper.engine.tools.FontInfoKt")
    // The cli wrappers pass paths as project properties so spaces survive.
    val fontinfoFile = providers.gradleProperty("fontinfoFile")
    val fontinfoPage = providers.gradleProperty("fontinfoPage")
    if (fontinfoFile.isPresent) {
        args(fontinfoFile.get(), "--page", fontinfoPage.getOrElse("1"))
    }
}

val perfReport = layout.buildDirectory.file("perf-report.txt")

// T17: text/image editing CLI. Usage:
//   ./gradlew :engine:pdfedit --args="add-text in.pdf 1 72 700 14 --text Hello --out out.pdf"
tasks.register<JavaExec>("pdfedit") {
    group = "verification"
    description = "Add text/images and manage page images (add-text, add-image, list-images, ...)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.pdfpaper.engine.tools.PdfEditCliKt")
    // Paths on the command line are resolved from the repo root, not engine/.
    workingDir = rootProject.projectDir
}

// T21: PDF creation CLI. Usage:
//   ./gradlew :engine:create --args="create blank --size A4 --out out.pdf"
tasks.register<JavaExec>("create") {
    group = "verification"
    description = "Create PDFs from a blank page, images or text (create/images-to-pdf/text-to-pdf)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.pdfpaper.engine.tools.PdfCreateCliKt")
    // Paths on the command line are resolved from the repo root, not engine/.
    workingDir = rootProject.projectDir
}

// T22: comment CLI. Usage:
//   ./gradlew :engine:comments --args="comments list in.pdf"
tasks.register<JavaExec>("comments") {
    group = "verification"
    description = "List, add, reply to and set the review status of PDF comments."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.pdfpaper.engine.tools.PdfCommentsCliKt")
    // Paths on the command line are resolved from the repo root, not engine/.
    workingDir = rootProject.projectDir
}

// T23: stamp CLI. Usage:
//   ./gradlew :engine:stamp --args="stamp list"
tasks.register<JavaExec>("stamp") {
    group = "verification"
    description = "List the stamp library and add stamp annotations to a PDF."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.pdfpaper.engine.tools.PdfStampCliKt")
    // Paths on the command line are resolved from the repo root, not engine/.
    workingDir = rootProject.projectDir
}

// T14: generates a scaled sibling corpus (the committed /testdata files are
// tiny structural fixtures) and times each engine phase against it.
tasks.register<JavaExec>("perfCheck") {
    group = "verification"
    description = "Profiles memory and time on a generated large PDF corpus (build/perf-report.txt)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.pdfpaper.engine.tools.PerfCheckKt")
    args(layout.buildDirectory.dir("perf-corpus").get().asFile.absolutePath, perfReport.get().asFile.absolutePath)
    outputs.file(perfReport)
}
