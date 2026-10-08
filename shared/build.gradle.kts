import com.android.build.gradle.LibraryExtension
plugins { kotlin("multiplatform"); kotlin("plugin.serialization") }
val includeAndroid = providers.gradleProperty("includeAndroid").orNull == "true"
if (includeAndroid) apply(plugin = "com.android.library")
kotlin {
    jvm()
    if (includeAndroid) androidTarget()
    iosArm64()
    iosSimulatorArm64()
    iosX64()
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        binaries.framework { baseName = "LilacShared"; isStatic = true }
    }
    jvmToolchain(17)
    sourceSets {
        commonMain.dependencies {
            implementation("com.fleeksoft.ksoup:ksoup:0.2.5")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
            implementation("io.ktor:ktor-client-core:3.1.3")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("io.ktor:ktor-client-mock:3.1.3")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
        jvmMain.dependencies { implementation("io.ktor:ktor-client-okhttp:3.1.3") }
        iosMain.dependencies { implementation("io.ktor:ktor-client-darwin:3.1.3") }
        if (includeAndroid) getByName("androidMain").dependencies {
            implementation("io.ktor:ktor-client-okhttp:3.1.3")
        }
    }
}
if (includeAndroid) extensions.configure<LibraryExtension> {
    namespace = "com.lilac.anime.shared"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
