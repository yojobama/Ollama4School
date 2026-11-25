plugins {
    id("com.android.library") version "8.7.3"
    id("com.vanniktech.maven.publish") version "0.30.0"
}

group = "io.github.yojob"          // TODO: use a namespace you have verified on Maven Central
version = "1.0.0"

android {
    namespace = "com.ollama4school"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("com.google.code.gson:gson:2.11.0")
}

mavenPublishing {
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
    // Sign only when a key is configured, so local builds work without one.
    if (providers.gradleProperty("signingInMemoryKey").isPresent ||
        providers.gradleProperty("signing.keyId").isPresent) {
        signAllPublications()
    }

    coordinates(group.toString(), "ollama4school", version.toString())

    pom {
        name.set("Ollama4School")
        description.set("Android client that finds Ollama PCs on the school network and balances questions across them.")
        inceptionYear.set("2026")
        url.set("https://github.com/yojob/Ollama4School")   // TODO
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("yojob")
                name.set("yojob")
                url.set("https://github.com/yojob")
            }
        }
        scm {
            url.set("https://github.com/yojob/Ollama4School")
            connection.set("scm:git:git://github.com/yojob/Ollama4School.git")
            developerConnection.set("scm:git:ssh://git@github.com/yojob/Ollama4School.git")
        }
    }
}
