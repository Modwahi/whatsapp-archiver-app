import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// التوقيع: من متغيرات البيئة (أسرار GitHub إن وُجدت)، أو keystore.properties محلياً،
// وإلا من المفتاح الثابت في signing/ (المستودع خاص) حتى تكون بصمة كل البناءات واحدة.
val signingProps = Properties().apply {
    val f = rootProject.file("keystore.properties").takeIf { it.exists() }
        ?: rootProject.file("signing/signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(env: String, prop: String): String? = System.getenv(env) ?: signingProps.getProperty(prop)
val releaseStore = signingValue("SIGNING_STORE_FILE", "storeFile")

// رقم الإصدار: من الـ tag (v1.3.0) إن وُجد، وإلا من version.txt في جذر المستودع
val semver = Regex("\\d+\\.\\d+\\.\\d+")
val tagVersion = (System.getenv("GITHUB_REF_NAME") ?: "").removePrefix("v").takeIf { semver.matches(it) }
    ?: rootProject.file("version.txt").takeIf { it.exists() }?.readText()?.trim()?.takeIf { semver.matches(it) }

android {
    namespace = "com.modwahi.waarchiver"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.modwahi.waarchiver"
        minSdk = 26
        targetSdk = 37
        versionName = tagVersion ?: "1.0.0"
        versionCode = tagVersion?.split(".")?.let { (a, b, c) -> a.toInt() * 10000 + b.toInt() * 100 + c.toInt() } ?: 10000
    }

    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = signingValue("SIGNING_STORE_PASSWORD", "storePassword")
                keyAlias = signingValue("SIGNING_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("SIGNING_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("user.timezone", "Asia/Aden")
            it.systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2/")
        }
    }

    packaging {
        resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES")
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.compose.tooling.preview)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.work.runtime)
    implementation(libs.okhttp)
    implementation(libs.coroutines.android)
    implementation(libs.play.services.auth)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test)
    testImplementation(libs.work.testing)
    debugImplementation(libs.compose.ui.test.manifest)
}
