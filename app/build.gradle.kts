import java.nio.file.Files
import java.io.File
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
dependencyLocking { lockAllConfigurations() }
val pickAudioVersion = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }

android {
    namespace = "com.pickaudio"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.pickaudio"
        minSdk = 36
        targetSdk = 36
        versionCode = pickAudioVersion.getProperty("versionCode").toInt()
        versionName = pickAudioVersion.getProperty("versionName")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
        create("validation") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            proguardFile("proguard-validation.pro")
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
        buildConfig = true
    }
    testBuildType = providers.gradleProperty("instrumentationBuildType").orElse("debug").get()
    sourceSets.getByName("validation").java.srcDir("src/debug/java")
    sourceSets.getByName("validation").manifest.srcFile("src/debug/AndroidManifest.xml")

    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    sourceSets.getByName("androidTest").assets.srcDir(layout.buildDirectory.dir("generated/updateFixtures"))

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            all {
                it.jvmArgs("-Dfile.encoding=UTF-8", "-Dsun.jnu.encoding=UTF-8")
                var testStaging: File? = null
                // Gradle's Windows test worker cannot reliably load classes from a Unicode path on JDK 17.
                it.doFirst {
                    if (System.getProperty("os.name").startsWith("Windows")) {
                        val original = it.classpath.files.filter { entry -> entry.exists() }
                        val unicodeEntries = original.filter { entry -> entry.absolutePath.any { char -> char.code > 127 } }
                        if (unicodeEntries.isNotEmpty()) {
                            val staging = Files.createTempDirectory("pickaudio-junit-").toFile()
                            testStaging = staging
                            val replacements = unicodeEntries.mapIndexed { index, entry ->
                                val dest = staging.resolve("$index/${entry.name}")
                                if (entry.isDirectory) entry.copyRecursively(dest, overwrite = true)
                                else { dest.parentFile.mkdirs(); entry.copyTo(dest, overwrite = true) }
                                entry to dest
                            }.toMap()
                            it.classpath = files(original.map { entry -> replacements[entry] ?: entry })
                        }
                    }
                }
                it.doLast { testStaging?.deleteRecursively() }
            }
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Public debug/test credentials only; release signing material is never read by this task.
val generateUpdateFixtures = tasks.register("generateUpdateFixtures") {
    dependsOn("validateSigningDebug")
    val output = layout.buildDirectory.dir("generated/updateFixtures")
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { mkdirs() }
        val toolDirectory = androidComponents.sdkComponents.sdkDirectory.get().asFile.resolve("build-tools/${android.buildToolsVersion}")
        val platformJar = androidComponents.sdkComponents.sdkDirectory.get().asFile.resolve("platforms/android-36/android.jar")
        val windows = System.getProperty("os.name").startsWith("Windows")
        val aapt = toolDirectory.resolve(if (windows) "aapt2.exe" else "aapt2")
        val javaBin = File(System.getProperty("java.home"), "bin/${if (windows) "java.exe" else "java"}")
        val wrongKey = directory.resolve("fixture-only.jks")
        if (!wrongKey.exists()) exec {
            commandLine(File(System.getProperty("java.home"), "bin/${if (windows) "keytool.exe" else "keytool"}"),
                "-genkeypair", "-keystore", wrongKey, "-alias", "fixture", "-storepass", "fixture-only", "-keypass", "fixture-only",
                "-keyalg", "RSA", "-validity", "36500", "-dname", "CN=PickAudio Isolated Test")
        }
        val signing = android.signingConfigs.getByName("debug")
        listOf("valid", "wrong-signer", "wrong-package").forEach { kind ->
            val manifest = directory.resolve("$kind.xml")
            val packageName = if (kind == "wrong-package") "com.pickaudio.fixture" else "com.pickaudio"
            manifest.writeText("""<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$packageName" android:versionCode="100000" android:versionName="99.0.0"><uses-sdk android:minSdkVersion="36" android:targetSdkVersion="36"/><application android:label="Isolated Test Fixture"/></manifest>""")
            val unsigned = directory.resolve("$kind-unsigned.apk")
            exec { commandLine(aapt, "link", "--manifest", manifest, "-I", platformJar, "-o", unsigned) }
            exec {
                commandLine(javaBin, "-jar", toolDirectory.resolve("lib/apksigner.jar"), "sign", "--ks",
                    if (kind == "wrong-signer") wrongKey else signing.storeFile!!,
                    "--ks-key-alias", if (kind == "wrong-signer") "fixture" else signing.keyAlias!!,
                    "--ks-pass", "pass:${if (kind == "wrong-signer") "fixture-only" else signing.storePassword!!}",
                    "--key-pass", "pass:${if (kind == "wrong-signer") "fixture-only" else signing.keyPassword!!}",
                    "--out", directory.resolve("$kind.apk"), unsigned)
            }
            unsigned.delete(); manifest.delete()
        }
        // This generated key is test-only, but it does not belong in an APK asset tree.
        wrongKey.delete()
    }
}
tasks.configureEach {
    if ((name.contains("AndroidTest") && name.endsWith("Assets")) || name.contains("lint", ignoreCase = true)) dependsOn(generateUpdateFixtures)
}

val generateThirdPartyAssets = tasks.register("generateThirdPartyAssets") {
    val runtime = configurations.named("releaseRuntimeClasspath")
    val output = layout.buildDirectory.dir("generated/thirdPartyAssets")
    inputs.files(runtime)
    inputs.file(rootProject.file("THIRD_PARTY_NOTICES.md"))
    inputs.dir(rootProject.file("licenses"))
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.resolve("third-party").apply { mkdirs() }
        rootProject.file("THIRD_PARTY_NOTICES.md").copyTo(directory.resolve("NOTICE.md"), overwrite = true)
        rootProject.file("licenses").listFiles().orEmpty().forEach { it.copyTo(directory.resolve(it.name), overwrite = true) }
        val artifacts = runtime.get().resolvedConfiguration.resolvedArtifacts.sortedBy { it.moduleVersion.id.toString() }
        directory.resolve("DEPENDENCIES.txt").writeText(artifacts.joinToString("\n") { it.moduleVersion.id.toString() } + "\n")
        val notices = StringBuilder()
        artifacts.forEach { artifact ->
            ZipFile(artifact.file).use { zip ->
                zip.entries().asSequence().filter { entry -> !entry.isDirectory && entry.name.uppercase().let {
                    it.contains("LICENSE") || it.contains("NOTICE") || it.endsWith("/COPYING")
                } && entry.size in 1..1048576 }.forEach { entry ->
                    notices.append("\n===== ${artifact.moduleVersion.id} / ${entry.name} =====\n")
                    zip.getInputStream(entry).bufferedReader().use { notices.append(it.readText()).append('\n') }
                }
            }
        }
        directory.resolve("UPSTREAM-NOTICES.txt").writeText(notices.toString())
    }
}
android.sourceSets.getByName("main").assets.srcDir(generateThirdPartyAssets)
tasks.configureEach {
    if ((name.startsWith("merge") && name.endsWith("Assets")) || name.contains("lint", ignoreCase = true)) dependsOn(generateThirdPartyAssets)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Media3 ExoPlayer & Session
    val media3Version = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-datasource:$media3Version")
    implementation("androidx.media3:media3-datasource-okhttp:$media3Version")
    implementation("androidx.media3:media3-database:$media3Version")

    // Room Database
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Network & Serialization
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // DataStore & Storage
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Coil for Compose image loading
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    "validationImplementation"("androidx.compose.ui:ui-test-manifest")
}
