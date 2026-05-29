plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.ocularai"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.ocularai"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // NOTE: If you turn Minification (R8/Proguard) 'true' later to shrink your app,
            // make sure to add TFLite reflection rules to your 'proguard-rules.pro'!
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

    // PERFECT BRO: This keeps the .tflite model uncompressed so memory-mapping works flawlessly
    androidResources {
        noCompress.add("tflite")
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/DEPENDENCIES",
                "META-INF/NOTICE",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE.txt"
            )
        }
    }
}

dependencies {
    // Core Android UI Components
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation("androidx.cardview:cardview:1.0.0")
    implementation(libs.activity)

    // Jetpack CameraX - Updated to a production-hardened version for smoother frame delivery
    val cameraVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraVersion")
    implementation("androidx.camera:camera-camera2:$cameraVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraVersion")
    implementation("androidx.camera:camera-view:$cameraVersion")

    // TensorFlow Lite Engine - This is the soul of your custom vision pipeline
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
    implementation("org.tensorflow:tensorflow-lite-task-vision:0.4.4")

    // FIXED: Stripped duplicate ML Kit dependencies to solve class collisions!

    // Testing Foundations
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}