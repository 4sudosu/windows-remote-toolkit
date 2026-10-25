plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.mimo.pro"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mimo.pro"
        minSdk = 24
        targetSdk = 34
        versionCode = 14
        versionName = "1.0"

        // No update gate
        buildConfigField("boolean", "UPDATE_GATE_ENABLED", "false")
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
        }
    }

    flavorDimensions += "version"
    productFlavors {
        create("normal") {
            dimension = "version"
            applicationId = "com.mimo.app"
            versionNameSuffix = "-normal"
            buildConfigField("boolean", "IS_PRO_VERSION", "false")
            buildConfigField("String", "APP_VARIANT", "\"normal\"")
            resValue("string", "app_name", "Mimo")
        }
        create("pro") {
            dimension = "version"
            applicationId = "com.mimo.pro"
            versionNameSuffix = "-pro"
            buildConfigField("boolean", "IS_PRO_VERSION", "true")
            buildConfigField("String", "APP_VARIANT", "\"pro\"")
            resValue("string", "app_name", "Mimo Pro")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            applicationVariants.all {
                val variant = this
                variant.outputs.all {
                    (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                        "Mimo${variant.flavorName.capitalize()}${variant.versionName}.apk"
                }
            }
        }
        debug {
            applicationVariants.all {
                val variant = this
                variant.outputs.all {
                    (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                        "Mimo${variant.flavorName.capitalize()}${variant.versionName}.apk"
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