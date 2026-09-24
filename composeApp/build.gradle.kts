import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig
import java.util.Properties

val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore/keystore.properties")
    if (file.exists()) load(file.inputStream())
}

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.google.services)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.firebaseAppDistribution)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    
    jvm("desktop")
    
    js(IR) {
        browser {
            commonWebpackConfig {
                outputFileName = "composeApp.js"
            }
        }
        binaries.executable()
    }
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }
    
    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.navigation.compose)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.coil.compose)
            
            // Firebase
            implementation(libs.firebase.auth)
            implementation(libs.firebase.firestore)
            implementation(libs.firebase.storage)

            // Explicit direct dependency, not just transitive: the web target was throwing
            // "RangeError: Invalid array length" from inside JobSupport.tryMakeCompleting/
            // finalizeFinishingState (the library's own multi-child job-completion exception
            // aggregation) on every Firestore write. Pin it forward past whatever transitive
            // version(s) Ktor/Compose/Firebase pull in, in case this is a fixed upstream bug.
            implementation(libs.kotlinx.coroutines.core)

            // Serialization
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            implementation(libs.kotlinx.datetime)

            // Ktor (weather lookups)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
        androidMain.dependencies {
            implementation(project.dependencies.platform(libs.firebase.bom))
            implementation(libs.firebase.analytics)
            implementation(libs.firebase.appdistribution)
            implementation(libs.firebase.messaging)
            implementation(libs.coil.network.okhttp)
            implementation(libs.androidx.appcompat)
            implementation(libs.material)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.biometric)
            implementation(libs.androidx.datastore)
            implementation(libs.androidx.browser)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.mlkit.text.recognition)
        }
        commonTest.dependencies {
            implementation(libs.junit)
        }
        val androidUnitTest by getting {
            dependencies {
                implementation(libs.junit)
            }
        }
        val androidInstrumentedTest by getting {
            dependencies {
                implementation(libs.androidx.junit)
                implementation(libs.androidx.espresso.core)
            }
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.coil.network.okhttp) // Use okhttp for desktop too
                implementation(libs.ktor.client.okhttp)
                // Provides Dispatchers.Main on the JVM (backed by the Swing EDT) - without
                // it, any coroutine that hops to Dispatchers.Main (e.g. Firebase Auth's
                // addAuthStateListener) crashes with "Module with the Main dispatcher is
                // missing" the moment it runs, since there's no Android main-thread dispatcher
                // to fall back on outside of Android.
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.pdfbox)
            }
        }
        val jsMain by getting {
            dependencies {
                implementation(libs.ktor.client.js)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.djransom.crewsync.MainKt"

        buildTypes {
            release {
                // ProGuard's shrinking fails outright on this dependency graph (10k+
                // unresolved references from grpc/protobuf/ktor's reflective access
                // patterns) rather than just warning. Shrinking was never load-bearing
                // here anyway - includeAllModules below already bundles the full JDK
                // instead of trying to trim it, for the same "don't fight the dependency
                // tree" reason.
                proguard {
                    isEnabled.set(false)
                }
            }
        }

        nativeDistributions {
            // jlink's module auto-detection (jdeps) misses modules only
            // touched via reflection/JNI in transitive deps - it already bit
            // us once with jdk.unsupported (see FirebaseManager.kt), and now
            // java.sql (pulled in by sqlite-jdbc for Firestore's local cache)
            // is missing the same way, crashing every Firestore call in the
            // packaged build with NoClassDefFoundError. Bundle the full JDK
            // instead of trying to enumerate every module a dependency might
            // reflectively need.
            includeAllModules = true
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Crewsync" // Changed from com.djransom.crewsync
            packageVersion = "1.0.8"
            description = "Construction Crew Management"
            copyright = "© 2026 Crewsync Team"
            vendor = "Crewsync"
            
            windows {
                menu = true
                shortcut = true
                upgradeUuid = "da7d34a6-9606-4423-8a32-4e613657386d" // Permanent ID for updates
            }
        }
    }
}

android {
    namespace = "com.djransom.crewsync"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.djransom.crewsync"
        minSdk = 24
        targetSdk = 36
        versionCode = 10
        versionName = "1.8.1"
    }
    externalNativeBuild {
        cmake {
            path = file("src/androidMain/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    signingConfigs {
        create("release") {
            if (keystoreProperties.containsKey("storeFile")) {
                storeFile = rootProject.file("keystore/" + keystoreProperties.getProperty("storeFile").substringAfterLast("/"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        getByName("debug") {
            firebaseAppDistribution {
                artifactType = "APK"
                releaseNotes = "Dashboard Redesign: SharePoint-style cards, Resizable layout, Weather, and Pro Markup tools."
            }
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    debugImplementation(compose.uiTooling)
}
