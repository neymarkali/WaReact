plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

// مفتاح توقيع ثابت (من أسرار GitHub). إن لم يكن موجوداً نرجع للمفتاح العشوائي القديم فلا ينكسر البناء.
// التوقيع الثابت = كل تحديث يُثبَّت فوق النسخة القديمة ويبقى الترخيص ومعرّف الجهاز كما هما.
val ksFile: String? = System.getenv("KEYSTORE_FILE")
val ksPass: String? = System.getenv("KEYSTORE_PASSWORD")
val ksAlias: String? = System.getenv("KEY_ALIAS")
val ksKeyPass: String? = System.getenv("KEY_PASSWORD")
val hasStableKey: Boolean =
    listOf(ksFile, ksPass, ksAlias, ksKeyPass).all { !it.isNullOrBlank() } && file(ksFile!!).exists()

android {
    namespace = "com.example.wareact"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.wareact"
        minSdk = 24
        targetSdk = 34
        // رقم البناء من GitHub Actions: يظهر في تقرير الدعم لنعرف أي نسخة ثُبّتت عند العميل
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "1.0.$build"
    }

    signingConfigs {
        if (hasStableKey) {
            create("stable") {
                storeFile = file(ksFile!!)
                storePassword = ksPass
                keyAlias = ksAlias
                keyPassword = ksKeyPass
            }
        }
    }
    buildTypes {
        debug {
            if (hasStableKey) signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = false
            if (hasStableKey) signingConfig = signingConfigs.getByName("stable")
        }
    }
    buildFeatures {
        viewBinding = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-auth")
}
