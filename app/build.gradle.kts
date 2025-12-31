plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

android {
    namespace = "com.msp1974.vacompanion"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.msp1974.vacompanion"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "0.9.5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        setProperty("archivesBaseName", "vaca-$versionName")
    }

    signingConfigs {
        create("release") {
            // Keep the keystore outside the repo. Path provided by user: ~/developer/keys/android
            storeFile = file("${System.getProperty("user.home")}/developer/keys/android/vaca-release.jks")
            // Put these in ~/.gradle/gradle.properties (recommended) so they are not committed:
            // VACA_KEYSTORE_PASSWORD=...
            // VACA_KEY_PASSWORD=...
            // Optionally override alias with VACA_KEY_ALIAS, otherwise defaults to "vaca"
            storePassword = providers.gradleProperty("VACA_KEYSTORE_PASSWORD").get()
            keyAlias = providers.gradleProperty("VACA_KEY_ALIAS").orElse("vaca").get()
            keyPassword = providers.gradleProperty("VACA_KEY_PASSWORD").get()
        }
    }

    buildTypes {
        applicationVariants.all {
            this.outputs
                .map { it as com.android.build.gradle.internal.api.ApkVariantOutputImpl }
                .forEach { output ->
                    var apkName = "vaca-${versionName}-${this.buildType.name}.apk"
                    output.outputFileName = apkName
                }
        }
        debug {
            isMinifyEnabled = false
        }
        release {
            isDebuggable = false
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.material3)
    implementation(libs.core.splashscreen)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics.ndk)
    implementation ("androidx.preference:preference-ktx:1.2.0")
    implementation ("com.jakewharton.timber:timber:5.0.1")
    implementation ("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    implementation ("io.github.z4kn4fein:semver:3.0.0")
    implementation ("com.squareup.okhttp3:okhttp:5.1.0")
    implementation (libs.androidx.webkit)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ui)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

}