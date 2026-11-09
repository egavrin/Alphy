import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
val readingTestBuild = providers.gradleProperty("alphyReadingTest").map(String::toBoolean).orElse(false)

android {
    namespace = "com.alpha.assistant"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.alpha.assistant"
        manifestPlaceholders["applicationLabel"] = "@string/app_name"
        minSdk = 36
        targetSdk = 37
        ndk { abiFilters += "arm64-v8a" }
        versionCode = 10
        versionName = "0.1.0-alpha.10"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        getByName("debug") {
            if (readingTestBuild.get()) {
                applicationIdSuffix = ".readingtest"
                manifestPlaceholders["applicationLabel"] = "Alphy · Чтение"
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    packaging { jniLibs { excludes += setOf("lib/armeabi-v7a/**", "lib/x86/**", "lib/x86_64/**") } }
    androidResources {
        noCompress += setOf("onnx", "litertlm")
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:CVS:thumbs.db:picasa.ini:*~:whisper:whisper-MIT.txt:silero"
    }
}
dependencies {
    implementation("org.commonmark:commonmark:0.30.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.30.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.30.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation(files("libs/sherpa-onnx-static-link-onnxruntime-1.13.4.aar"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    implementation("androidx.compose.material3:material3:1.5.0-beta02")
    implementation("androidx.navigation3:navigation3-runtime:1.2.0")
    implementation("androidx.navigation3:navigation3-ui:1.2.0")
    implementation("androidx.compose.material3.adaptive:adaptive-navigation3:1.3.0")
    implementation("androidx.window:window:1.5.1")
    implementation("androidx.navigationevent:navigationevent-compose:1.1.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.glance:glance-appwidget:1.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.javascriptengine:javascriptengine:1.1.1")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.window:window-testing:1.5.1")
}

val embeddingModel = layout.projectDirectory.file("src/main/assets/semantic/embeddinggemma-2-text-270m.litertlm")
val verifyEmbeddingModel by tasks.registering {
    inputs.file(embeddingModel)
    doLast {
        val model = embeddingModel.asFile
        check(model.length() == 164_626_432L) { "Invalid embedding model size. Run git lfs pull." }
        val digest = MessageDigest.getInstance("SHA-256")
        model.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        check(hash == "2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb") {
            "Invalid embedding model checksum. Run git lfs pull."
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(verifyEmbeddingModel) }
