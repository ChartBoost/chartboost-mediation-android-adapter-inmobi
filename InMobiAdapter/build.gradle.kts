import org.jfrog.gradle.plugin.artifactory.task.ArtifactoryTask

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
    id("com.jfrog.artifactory")
}

repositories {
    google()
    mavenCentral()
    maven("https://cboost.jfrog.io/artifactory/private-helium/") {
        credentials {
            username = System.getenv("JFROG_USER")
            password = System.getenv("JFROG_PASS")
        }
    }
    maven("https://cboost.jfrog.io/artifactory/helium/")
}

android {
    namespace = "com.chartboost.helium.inmobiadapter"
    compileSdk = 33

    defaultConfig {
        minSdk = 21
        targetSdk = 33
        // If you touch the following line, don't forget to update scripts/get_rc_version.zsh
        android.defaultConfig.versionName = System.getenv("VERSION_OVERRIDE") ?: "4.10.1.1.0"
        buildConfigField("String", "HELIUM_INMOBI_ADAPTER_VERSION", "\"${android.defaultConfig.versionName}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "location"
    productFlavors {
        create("local")
        create("remote")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    "localImplementation"(project(":Helium"))

    // For external usage, please use the following production dependency.
    // You may choose a different release version.
    // TODO: Change this to 4.+ when it's released
    "remoteImplementation"("com.chartboost:helium:+")

    // Partner SDK
    implementation("com.inmobi.monetization:inmobi-ads:10.1.1")

    //Partner SDK Dependencies
    implementation("androidx.appcompat:appcompat:1.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")
}

artifactory {
    clientConfig.isIncludeEnvVars = true
    setContextUrl("https://cboost.jfrog.io/artifactory")

    publish {
        repository {
            // If this is a release build, push to the public "helium" artifactory.
            // Otherwise, push to the "private-helium" artifactory.
            var isReleaseBuild = "true" == System.getenv("HELIUM_IS_RELEASE")
            if (isReleaseBuild) {
                setRepoKey("helium")
            } else {
                setRepoKey("private-helium")
            }
            // Set the environment variables for these to be able to push to artifactory.
            setUsername(System.getenv("JFROG_USER"))
            setPassword(System.getenv("JFROG_PASS"))
        }

        defaults {
            publications("InMobiAdapter", "aar")
            setPublishArtifacts(true)
            setPublishPom(true)
        }
    }
}

afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("remoteRelease") {
                from(components["remoteRelease"])

                var adapterName = "inmobi"
                groupId = "com.chartboost"
                artifactId = "helium-adapter-$adapterName"
                version = if (project.hasProperty("snapshot")) {
                    android.defaultConfig.versionName + rootProject.ext["SNAPSHOT"]
                 } else {
                    android.defaultConfig.versionName
                 }

                pom {
                    name.set("Helium Adapter InMobi")
                    description.set("Better monetization. Powered by bidding")
                    url.set("https://www.chartboost.com/helium/")

                    licenses {
                        license {
                            name.set("https://answers.chartboost.com/en-us/articles/200780239")
                        }
                    }

                    developers {
                        developer {
                            id.set("chartboostmobile")
                            name.set("chartboost mobile")
                            email.set("support@chartboost.com")
                        }
                    }

                    scm {
                        var gitUrl = "https://github.com/ChartBoost/helium-android-adapter-$adapterName"
                        url.set(gitUrl)
                        connection.set(gitUrl)
                        developerConnection.set(gitUrl)
                    }
                }
            }
        }
    }

    tasks.named<ArtifactoryTask>("artifactoryPublish") {
        publications(publishing.publications["remoteRelease"])
    }
}