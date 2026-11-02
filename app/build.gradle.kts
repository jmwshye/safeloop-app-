plugins {
 id("com.android.application")
 id("org.jetbrains.kotlin.android")
 id("org.jetbrains.kotlin.plugin.compose")
}
if (file("google-services.json").exists()) { apply(plugin = "com.google.gms.google-services") }
android {
 namespace = "com.safeloop.app"
 compileSdk = 35
 defaultConfig { applicationId = "com.safeloop.app"; minSdk = 26; targetSdk = 35; versionCode = 2; versionName = "0.2.0"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
 defaultConfig { ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") } }
 androidResources { noCompress += "tflite" }
 buildFeatures { compose = true; buildConfig = true }
 val apiUrl = providers.gradleProperty("SAFELOOP_API_URL").orNull
 fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
 buildTypes {
  getByName("debug") { buildConfigField("String", "API_BASE_URL", quoted(apiUrl ?: "http://10.0.2.2:3000")) }
  getByName("release") { buildConfigField("String", "API_BASE_URL", quoted(apiUrl ?: "")) }
 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
 testOptions { unitTests { isIncludeAndroidResources = true; all { it.systemProperty("roborazzi.test.record", "true"); it.maxHeapSize = "2g"; val temp = layout.buildDirectory.dir("tmp/test-jvm").get().asFile; temp.mkdirs(); it.systemProperty("java.io.tmpdir", temp.path) } } }
}
dependencies {
 constraints {
  implementation("androidx.fragment:fragment:1.8.9") {
   because("FCM의 이전 Fragment 의존성을 Activity Result API와 호환되는 버전으로 맞춤")
  }
 }
 testImplementation("junit:junit:4.13.2")
 testImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
 testImplementation("androidx.compose.ui:ui-test-junit4")
 debugImplementation("androidx.compose.ui:ui-test-manifest")
 testImplementation("org.robolectric:robolectric:4.13")
 testImplementation("io.github.takahirom.roborazzi:roborazzi:1.32.2")
 testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.32.2")
 implementation(platform("androidx.compose:compose-bom:2024.12.01"))
 implementation("androidx.activity:activity-compose:1.9.3")
 implementation("androidx.compose.ui:ui")
 implementation("androidx.compose.foundation:foundation")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.compose.ui:ui-tooling-preview")
 debugImplementation("androidx.compose.ui:ui-tooling")
 androidTestImplementation("androidx.test.ext:junit:1.2.1")
 androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
 implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
 implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
 implementation("androidx.core:core-ktx:1.15.0")
 implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
 implementation("com.google.firebase:firebase-messaging")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
 implementation("androidx.work:work-runtime-ktx:2.10.0")
 implementation("com.google.mediapipe:tasks-vision:0.10.14")
}
