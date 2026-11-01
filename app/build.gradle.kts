import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy

plugins {
  alias(libs.plugins.android.application)
  // alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

android {
  namespace = "com.jarvis.assistant"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.siyaai.qwrzp"
    minSdk = 26
    targetSdk = 35
    versionCode = 146
    versionName = "1.0.146"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // Do not remove or modify these signingConfigs. They are necessary for building, installing,
  // and updating Android apps in AI Studio.
  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    viewBinding = true
    buildConfig = true
  }
  testOptions {
    unitTests {
      isIncludeAndroidResources = true
      isReturnDefaultValues = true
    }
  }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

ksp {
  arg("room.schemaLocation", "$projectDir/schemas")
}

tasks.register("checkReleaseApiKeySafety") {
  doLast {
    val envFile = rootProject.file(".env")
    if (envFile.exists()) {
      val envText = envFile.readText()
      val match = Regex("""(?m)^\s*GEMINI_API_KEY\s*=\s*(.+)$""").find(envText)
      val keyValue = match?.groupValues?.get(1)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'")
      if (!keyValue.isNullOrBlank()) {
        throw GradleException(
          "Security Violation: .env contains a non-empty GEMINI_API_KEY for a release build. Remove GEMINI_API_KEY from .env before building release APK or bundle."
        )
      }
    }
  }
}

tasks.matching { it.name in listOf("assembleRelease", "bundleRelease") }.configureEach {
  dependsOn("checkReleaseApiKeySafety")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation("androidx.appcompat:appcompat:1.7.0")
  implementation("com.google.android.material:material:1.12.0")
  implementation("androidx.constraintlayout:constraintlayout:2.1.4")
  implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
  implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation("androidx.activity:activity-ktx:1.10.1")
  implementation("androidx.recyclerview:recyclerview:1.3.2")
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.okhttp)
  implementation("com.google.code.gson:gson:2.10.1")
  implementation("androidx.webkit:webkit:1.12.0")
  implementation("net.java.dev.jna:jna:5.13.0@aar")
  implementation("com.alphacephei:vosk-android:0.3.47@aar")
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.camera.core)
  implementation(libs.androidx.camera.camera2)
  implementation(libs.androidx.camera.lifecycle)
  implementation(libs.androidx.camera.view)
  implementation("androidx.security:security-crypto:1.1.0-alpha06")
  implementation("androidx.work:work-runtime-ktx:2.9.1")
  "ksp"(libs.androidx.room.compiler)

  testImplementation(libs.junit)
}
