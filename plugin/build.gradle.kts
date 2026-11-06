import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SonatypeHost.Companion.CENTRAL_PORTAL
import org.gradle.api.JavaVersion.VERSION_1_8
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.dokka)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.vanniktech.maven.publish)
}

group = "io.github.tiper"
version = "3.2.0"

kotlin {

    // JVM & Android
    jvm {
        @Suppress("OPT_IN_USAGE")
        compilerOptions {
            jvmTarget.set(JVM_1_8)
        }
    }
    androidTarget {
        publishLibraryVariants("release")
        @Suppress("OPT_IN_USAGE")
        compilerOptions {
            jvmTarget.set(JVM_1_8)
        }
    }

    // JavaScript
    js(IR) {
        browser()
        nodejs()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        nodejs()
    }

    // Native targets
    // See: https://kotlinlang.org/docs/native-target-support.html

    // Tier 1
    macosX64()
    macosArm64()
    iosArm64()
    iosX64()
    iosSimulatorArm64()

    // Tier 2
    linuxArm64()
    linuxX64()
    watchosArm32()
    watchosArm64()
    watchosX64()
    watchosSimulatorArm64()
    tvosArm64()
    tvosX64()
    tvosSimulatorArm64()

    // Tier 3
    androidNativeArm32()
    androidNativeArm64()
    androidNativeX86()
    androidNativeX64()
    mingwX64()
    watchosDeviceArm64()

    // Groups JVM and native so tests needing real threads can be shared. JS and wasm are excluded
    // because they are single-threaded and have no runBlocking.
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("concurrent") {
                withJvm()
                withNative()
            }
        }
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

// Turns the README into the landing page of the docs site.
val dokkaModuleDoc by tasks.registering {
    val readme = rootProject.file("README.md")
    val moduleDoc = layout.buildDirectory.file("dokka/includes/Module.md")
    inputs.file(readme)
    outputs.file(moduleDoc)
    doLast {
        // Dokka can't render the badges.
        val guide = readme.readLines().drop(1).filterNot { it.startsWith("[![") }.joinToString("\n").trimStart()
        moduleDoc.get().asFile.writeText("# Module ktor-client-deduplication\n\n$guide")
    }
}

dokka {
    moduleName = "ktor-client-deduplication"
    dokkaSourceSets.named("commonMain") {
        includes.from(dokkaModuleDoc)
        sourceLink {
            localDirectory = file("src")
            remoteUrl("https://github.com/tiper/KtorRequestDeduplication/tree/main/plugin/src")
        }
    }
}

mavenPublishing {
    // Keeps Dokka out of the published artifacts. It's only for the docs site.
    configure(KotlinMultiplatform(javadocJar = JavadocJar.Empty(), androidVariantsToPublish = listOf("release")))
    publishToMavenCentral(CENTRAL_PORTAL)
    signAllPublications()
    coordinates(artifactId = "ktor-client-deduplication")
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
