import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.vaultx.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.vaultx.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val props = Properties()
            val propsFile = rootProject.file("keystore.properties")
            if (propsFile.exists()) {
                propsFile.inputStream().use { props.load(it) }
            }
            storeFile = file(props.getProperty("storeFile") ?: System.getenv("VAULTX_STORE_FILE") ?: "")
            storePassword = props.getProperty("storePassword") ?: System.getenv("VAULTX_STORE_PASSWORD")
            keyAlias = props.getProperty("keyAlias") ?: System.getenv("VAULTX_KEY_ALIAS")
            keyPassword = props.getProperty("keyPassword") ?: System.getenv("VAULTX_KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
                "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
                "META-INF/versions/9/module-info.class",
            )
        }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.material3)
    implementation(libs.material.icons.extended)
    implementation(libs.navigation3.ui)
    implementation(libs.navigation3.runtime)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.process)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.datastore.preferences)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.common)
    implementation(libs.exifinterface)
    implementation(libs.coil.compose)
    implementation(libs.coil.core)
    implementation(libs.tink.android)
    implementation(libs.bcprov)
    implementation(libs.biometric)
    implementation(libs.fragment)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
