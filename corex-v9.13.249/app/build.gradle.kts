plugins {
    id("com.android.application")
}

android {
    namespace = "com.mohan.mylifemanager"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mohan.mylifemanager"
        minSdk = 26
        targetSdk = 35
        versionCode = 913249
        versionName = "9.13.249-corex"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }
}

dependencies {
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.work:work-runtime:2.9.1")
}
