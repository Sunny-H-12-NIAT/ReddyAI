 plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.reddysai.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.reddysai.app"
        minSdk = 29        // Android 10 minimum — needed for floating overlay reliability
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-alpha"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Inject OAuth Web Client ID at build time — set this in local.properties
        val oauthClientId = project.findProperty("OAUTH_WEB_CLIENT_ID") as String?
            ?: "YOUR_OAUTH_WEB_CLIENT_ID.apps.googleusercontent.com"
        buildConfigField("String", "OAUTH_WEB_CLIENT_ID", "\"$oauthClientId\"")

        // ── JNI / native layer ────────────────────────────────────────────
        // Only arm64-v8a: the prebuilt libllama.so from the WSL build is arm64 only.
        // Remove this filter once/if x86_64 builds are also delivered.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // Compile only llama_jni.cpp; link against prebuilt .so files in jniLibs/arm64-v8a/.
    // The full llama.cpp source tree is NOT built here — prebuilts are used directly.
    // Activate this block by dropping the .so files + headers into place (see CMakeLists.txt).
    // externalNativeBuild {
    //     cmake {
    //         path = file("src/main/cpp/CMakeLists.txt")
    //         version = "3.22.1"
    //     }
    // }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            // Google API client pulls in duplicate META-INF files
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
            excludes += "META-INF/INDEX.LIST"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.accompanist.permissions)
    implementation(libs.androidx.splashscreen)

    // Google Sign-In (Play Services Auth)
    implementation(libs.google.play.services.auth)

    // Gmail API
    implementation(libs.google.api.client.android)
    implementation(libs.google.api.services.gmail)
    implementation(libs.google.oauth.client.jetty)
    implementation(libs.google.auth.library.oauth2)

    // HTTP & AI / Supabase REST client
    implementation(libs.okhttp)

    debugImplementation(libs.androidx.ui.tooling)
}
