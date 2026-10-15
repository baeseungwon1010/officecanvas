plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.baeseungwon1010.officecanvas.sample"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.baeseungwon1010.officecanvas.sample"
        minSdk = 24
        targetSdk = 34
        versionCode = 7
        versionName = "0.5.1"
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
    implementation(project(":officecanvas"))
}
