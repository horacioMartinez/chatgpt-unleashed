import java.util.Properties

plugins { id("com.android.application") }

val releaseSigningFile = file(
    providers.environmentVariable("UNLEASHED_SIGNING_PROPERTIES").orNull
        ?: "${System.getProperty("user.home")}/.config/unleashed/release-signing.properties"
)
val releaseSigning = Properties().apply {
    if (releaseSigningFile.isFile) releaseSigningFile.inputStream().use { load(it) }
}
val hasReleaseSigning = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .all { !releaseSigning.getProperty(it).isNullOrBlank() }

android {
    namespace = "com.horacio.unleashed"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.horacio.unleashed"
        minSdk = 30
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.1"
    }
    buildFeatures { buildConfig = true }
    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-dev"
        }
        getByName("release") {
            isDebuggable = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val verifyReleaseSigning by tasks.registering {
    doLast {
        check(hasReleaseSigning) { "Release signing is not configured. Set UNLEASHED_SIGNING_PROPERTIES to the existing signing properties file." }
        check(file(releaseSigning.getProperty("storeFile")).isFile) { "Release keystore is missing." }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(verifyReleaseSigning) }

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
