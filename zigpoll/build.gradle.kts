plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

android {
    namespace = "com.zigpoll"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    /* The release variant is what gets published (see below). */
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

/* JitPack installs the SDK by running `publishToMavenLocal` with its own
   group and version (-Pgroup, -Pversion), so the library has to define a
   publication for that task to exist. Without one its build failed and the
   coordinate in the README resolved to nothing. */
afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                from(components["release"])
            }
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
}
