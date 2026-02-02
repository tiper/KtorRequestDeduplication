import org.gradle.api.JavaVersion.VERSION_1_8

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.vanniktech.maven.publish)
}

group = "io.github.tiper"
version = "2.0.0"

kotlin {
    // JVM & Android
    jvm()
    androidTarget {
        publishLibraryVariants("release")
        compilations.all {
            kotlinOptions {
                jvmTarget = "1.8"
            }
        }
    }

    // iOS
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    // tvOS
    tvosX64()
    tvosArm64()
    tvosSimulatorArm64()

    // watchOS
    watchosArm32()
    watchosArm64()
    watchosX64()
    watchosSimulatorArm64()

    // macOS
    macosX64()
    macosArm64()

    // Linux
    linuxX64()

    // Windows (MinGW)
    mingwX64()

    // JavaScript
    js(IR) {
        browser()
        nodejs()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.ktor)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.atomicfu)
                implementation(libs.kotlin.test)
                implementation(libs.ktor.mock)
                implementation(libs.coroutines.test)
            }
        }

        val androidUnitTest by getting {
            dependencies {
                implementation(libs.slf4j.simple)
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(libs.slf4j.simple)
            }
        }
    }
}

android {
    namespace = "io.github.tiper.ktor.client.plugins.deduplication"
    compileSdk =
        libs.versions.android.compileSdk
            .get()
            .toInt()
    defaultConfig {
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
    }
    compileOptions {
        sourceCompatibility = VERSION_1_8
        targetCompatibility = VERSION_1_8
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates(group.toString(), "ktor-client-deduplication", version.toString())
    pom {
        name = "Ktor Client Request Deduplication"
        description = "A Kotlin Multiplatform library that prevents duplicate concurrent HTTP requests in Ktor clients"
        inceptionYear = "2026"
        url = "https://github.com/tiper/KtorRequestDeduplication"
        licenses {
            license {
                name = "Apache License 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0"
            }
        }
        developers {
            developer {
                id = "tiper"
                name = "Tiago Pereira"
                email = "1698241+tiper@users.noreply.github.com"
            }
        }
        scm {
            url = "https://github.com/tiper/KtorRequestDeduplication"
            connection = "scm:git:git://github.com/tiper/KtorRequestDeduplication.git"
            developerConnection = "scm:git:ssh://git@github.com/tiper/KtorRequestDeduplication.git"
        }
    }
}
