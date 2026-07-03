plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.understory.backup"
    compileSdk = 35

    defaultConfig {
        minSdk = 33
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        lintConfig = file("../lint.xml")
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
    // BouncyCastle is exposed as `api` from :common-security, so we get
    // Argon2BytesGenerator + Base32 transitively through it. Keeping the
    // dep graph centralized in :common-security avoids version skew.
    implementation(project(":common-security"))

    // JUnit for the envelope + codec test suites. No Robolectric needed
    // here — the envelope is pure java.io and the codec only uses the
    // pure-JVM paths of :common-security's Crypto (argon2id + AES-GCM).
    testImplementation("junit:junit:4.13.2")
}
