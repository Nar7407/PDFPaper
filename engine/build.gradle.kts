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
