plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

group = "io.github.baeseungwon1010"
version = "0.2.1"

android {
    namespace = "io.github.baeseungwon1010.officecanvas"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        // Robolectric renders through the real android.graphics stack only with
        // Android resources on the test classpath.
        unitTests.isIncludeAndroidResources = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "officecanvas"
            afterEvaluate { from(components["release"]) }
        }
    }
}

// tools/compare.sh passes a document through to RenderFileTool this way.
tasks.withType<Test>().configureEach {
    listOf("officecanvas.input", "officecanvas.output", "officecanvas.longSide").forEach { key ->
        project.findProperty(key)?.let { systemProperty(key, it) }
    }
}
