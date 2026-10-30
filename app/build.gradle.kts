plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.alpha.assistant"
    compileSdk = 37
    ndkVersion = "27.0.12077973"
    defaultConfig {
        applicationId = "com.alpha.assistant"
        minSdk = 29
        targetSdk = 37
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { arguments += "-DCMAKE_BUILD_TYPE=Release" } }
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    packaging { jniLibs { excludes += setOf("lib/armeabi-v7a/**", "lib/x86/**", "lib/x86_64/**") } }
    androidResources { noCompress += listOf("onnx", "bin", "f32") }
}
dependencies {
    implementation(files("libs/sherpa-onnx-static-link-onnxruntime-1.13.4.aar"))
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.27.0")
    implementation("com.github.wendykierp:JTransforms:3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2025.12.00"))
    implementation("androidx.activity:activity-compose:1.12.2")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("io.github.dokar3:quickjs-kt:1.0.14")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
