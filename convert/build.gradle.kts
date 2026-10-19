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
    // The converters read PDFs and write PDFs with the app's own engine. No
    // third-party Office or PDF library is used; OOXML is written/read as a
    // ZIP package of XML parts with java.util.zip and a small XML writer.
    implementation(project(":engine"))
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.24")
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test> {
    useJUnit()
}

// CLI: ./gradlew :convert:convert --args="pdf-to-pptx in.pdf --out out.pptx"
// (or via the repo-root `cli` wrapper: cli convert pdf-to-pptx ...). Paths are
// resolved from the repo root, not convert/.
tasks.register<JavaExec>("convert") {
    group = "verification"
    description = "Convert between PDF and office formats (pdf-to-pptx, pdf-to-docx)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.pdfpaper.convert.tools.ConvertCliKt")
    workingDir = rootProject.projectDir
}

val pptxCorpusReport = layout.buildDirectory.file("pptx-corpus-report.txt")

// Acceptance harness: converts every /testdata PDF to .pptx and validates the
// package, writing convert/build/pptx-corpus-report.txt.
tasks.register<JavaExec>("pptxCorpusCheck") {
    group = "verification"
    description = "Converts every corpus PDF to PPTX and validates the packages."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.pdfpaper.convert.tools.PptxCorpusCheckKt")
    args(
        rootProject.file("testdata").absolutePath,
        layout.buildDirectory.dir("pptx-corpus").get().asFile.absolutePath,
        pptxCorpusReport.get().asFile.absolutePath
    )
    inputs.dir(rootProject.file("testdata"))
    outputs.file(pptxCorpusReport)
}

val docxCorpusReport = layout.buildDirectory.file("docx-corpus-report.txt")

// Acceptance harness: converts every /testdata PDF to .docx (flow and exact)
// and validates the packages, writing convert/build/docx-corpus-report.txt.
tasks.register<JavaExec>("docxCorpusCheck") {
    group = "verification"
    description = "Converts every corpus PDF to DOCX in both modes and validates the packages."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.pdfpaper.convert.tools.DocxCorpusCheckKt")
    args(
        rootProject.file("testdata").absolutePath,
        layout.buildDirectory.dir("docx-corpus").get().asFile.absolutePath,
        docxCorpusReport.get().asFile.absolutePath
    )
    inputs.dir(rootProject.file("testdata"))
    outputs.file(docxCorpusReport)
}
