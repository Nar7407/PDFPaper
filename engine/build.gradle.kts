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

val perfReport = layout.buildDirectory.file("perf-report.txt")

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
