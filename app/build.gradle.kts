import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

// Release signing is read from keystore.properties, which is intentionally kept
// out of version control (see .gitignore and AGENTS.md). When it is absent the
// release variant falls back to the debug key so `assembleRelease` still builds
// an installable APK for verification.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        FileInputStream(keystorePropertiesFile).use { load(it) }
    }
}
val hasReleaseKeystore = keystorePropertiesFile.exists()

android {
    namespace = "com.pdfpaper.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pdfpaper.app"
        minSdk = 26
        targetSdk = 34
        // Rebuild handed to the owner as PDFPaperv0.1.1.apk (T43-T45 included).
        versionCode = 3
        versionName = "0.1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // T14: shrink and obfuscate the release build.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    testOptions {
        // TocSplitViewModelTest constructs an Application; the android.jar stub
        // constructor throws "Stub!" without this.
        unitTests.isReturnDefaultValues = true
    }
    sourceSets {
        // T68b: fixtures shared by the JVM unit tests and the device tests.
        getByName("test").java.srcDir("src/sharedTest/java")
        getByName("androidTest").java.srcDir("src/sharedTest/java")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    // Task-requested tool icons. Apache License 2.0, same as Compose; it ships
    // the extended Material icon set locally (no runtime service or account).
    implementation("androidx.compose.material:material-icons-extended")
    // Task T48: the home-screen widget is written with Jetpack Glance
    // (Apache License 2.0). It draws the same recent list as the app and has no
    // background service or network access; the widget updates only when the
    // recent list or the launcher privacy setting changes.
    implementation("androidx.glance:glance-appwidget:1.1.1")

    // Task T54: CanvasFrontBufferedRenderer gives the focused stylus canvas a
    // front-buffered + multi-buffered path (Android 10+) so a new segment is
    // presented without waiting for a full frame. The normal page drawing path
    // falls back to Compose Canvas. Apache License 2.0.
    implementation("androidx.graphics:graphics-core:1.0.1")

    // Pure-Kotlin PDF engine (no Android dependencies); added in T9 so the
    // viewer can parse documents and build display lists in-process.
    implementation(project(":engine"))

    // Pure-Kotlin office converters (no Android dependencies); added with the
    // open-office-files flow so .docx/.pptx can be turned into a PDF on device.
    implementation(project(":convert"))

    // Pure-Kotlin signature validation (JDK JCA + own DER/CMS reader); the
    // Signatures panel reads its SignatureReport and renders plain language.
    implementation(project(":signatures"))

    // Task T49: static PDF safety analysis (actions, attachments, links and
    // structure). It never executes JavaScript or opens links.
    implementation(project(":safety"))

    // Task T50: the search index is a local Room database. Room's FTS4 support
    // provides the per-page full-text table; nothing leaves the device.
    // Apache License 2.0.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    // Task T50: incremental indexing runs as deferrable background work with
    // constraints and cancellation. Apache License 2.0.
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Task UI-2: on-device scanning and OCR. The scanner UI/models are
    // downloaded through Google Play services (ML Kit Terms of Service,
    // ~300 KB app impact); text recognition uses the unbundled Latin and
    // Devanagari models (~260 KB per script, downloaded on first use).
    // Chosen by the owner after the license/size question.
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.0")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-devanagari:16.0.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    // T66: the chapter-split tests build tiny PDFs with the engine's own test
    // fixture builder (buildTestPdf). Built-in Gradle test fixtures; no new
    // dependency is downloaded.
    testImplementation(testFixtures(project(":engine")))
    androidTestImplementation(testFixtures(project(":engine")))
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    // First-party Compose semantics driver for navigation and search UI tests.
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

