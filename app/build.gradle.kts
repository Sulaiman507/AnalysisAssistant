plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    // الحزمة مؤقتة وقابلة للتغيير — انظر README
    namespace = "com.musaed.analysis"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.musaed.analysis"
        minSdk = 26
        targetSdk = 36
        versionCode = 16
        versionName = "0.1.15-picker-download"
    }

    buildTypes {
        debug {
            // debug فقط: يسمح بتثبيت نسخة الفحص بجانب أي نسخة لاحقة
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":mcp"))
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
}
