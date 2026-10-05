import java.net.URI
import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}
val wakeWorkerUrl = providers.gradleProperty("wakeWorkerUrl").orElse(providers.environmentVariable("FAMILY_LOCATION_WAKE_WORKER_URL")).orElse("https://family-location-wake.huytoan0979928450.workers.dev").get()
// Local private release input only. CI/debug APKs remain unprovisioned.
val bootstrapToken = providers.environmentVariable("FAMILY_LOCATION_CHILD_BOOTSTRAP").orElse("").get()
require(bootstrapToken.isEmpty() || (Regex("[A-Za-z0-9_-]{43}").matches(bootstrapToken) &&
    Base64.getUrlEncoder().withoutPadding().encodeToString(Base64.getUrlDecoder().decode(bootstrapToken)) == bootstrapToken)) { "Invalid role bootstrap format" }
require(!(System.getenv("CI") == "true" && bootstrapToken.isNotEmpty())) { "Bootstrap injection is forbidden in CI" }
val workerOrigin = URI(wakeWorkerUrl)
require(workerOrigin.scheme == "https" && !workerOrigin.host.isNullOrBlank() && workerOrigin.rawUserInfo == null &&
    workerOrigin.rawQuery == null && workerOrigin.rawFragment == null && (workerOrigin.path.isNullOrEmpty() || workerOrigin.path == "/")) { "Worker URL must be a public HTTPS origin without endpoint/path" }

android {
    namespace = "com.family.child"
    compileSdk = 36
    defaultConfig { applicationId = "com.family.child"; minSdk = 26; targetSdk = 36; versionCode = 35; versionName = "2.3.1"; buildConfigField("String", "WAKE_WORKER_URL", "\"$wakeWorkerUrl\"") }
    buildTypes {
        getByName("debug") { buildConfigField("String", "BOOTSTRAP_TOKEN", "\"\"") }
        getByName("release") { buildConfigField("String", "BOOTSTRAP_TOKEN", "\"$bootstrapToken\"") }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(project(":core"))
    implementation(project(":enrollment"))
    implementation(platform("androidx.compose:compose-bom:2026.06.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("com.google.android.gms:play-services-location:21.4.0")
    implementation(platform("com.google.firebase:firebase-bom:34.17.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
}


dependencies {
    implementation("androidx.fragment:fragment-ktx:1.8.9")
}


dependencies {
    implementation("androidx.compose.animation:animation")
}


dependencies {
    implementation("androidx.work:work-runtime-ktx:2.10.5")
}


dependencies {
    implementation("com.google.firebase:firebase-messaging:25.1.1")
}

android { testOptions { unitTests { isIncludeAndroidResources = true } } }
dependencies { testImplementation("junit:junit:4.13.2"); testImplementation("org.robolectric:robolectric:4.17") }
