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
 buildFeatures { compose = true; buildConfig = true }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
dependencies {
 testImplementation("junit:junit:4.13.2")
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
 implementation("com.google.firebase:firebase-auth")
 implementation("com.google.firebase:firebase-firestore")
 implementation("com.google.firebase:firebase-functions")
 implementation("com.google.firebase:firebase-storage")
 implementation("com.google.firebase:firebase-messaging")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
 implementation("androidx.work:work-runtime-ktx:2.10.0")
}
