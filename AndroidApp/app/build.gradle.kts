plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.runtimebroker.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.runtimebroker.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 11
        versionName = "4.3"

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }

        // Update gate switch (WinSysMonitor V2 pattern): standard builds must
        // verify releases/latest; the "noupdate" build skips the check.
        buildConfigField("boolean", "UPDATE_GATE_ENABLED", "true")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            applicationVariants.all {
                val variant = this
                variant.outputs.all {
                    (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                        "RuntimeBroker${variant.versionName}.apk"
                }
            }
        }
        debug {
            applicationVariants.all {
                val variant = this
                variant.outputs.all {
                    (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                        "RuntimeBroker${variant.versionName}.apk"
                }
            }
        }
        create("noupdate") {
            initWith(getByName("debug"))
            // Same package/version so it installs over any 4.3 build.
            versionNameSuffix = "-noupdate"
            buildConfigField("boolean", "UPDATE_GATE_ENABLED", "false")
            applicationVariants.all {
                val variant = this
                variant.outputs.all {
                    (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                        "RuntimeBroker${variant.versionName}.apk"
                }
            }
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
        viewBinding = true
        buildConfig = true
    }
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("libnode/bin/")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.photoview)
}