plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}

// Shared coordinates for the published SDK artifacts: com.qartvelo.ads:core and
// com.qartvelo.ads:admob everywhere. JitPack sets GROUP/ARTIFACT (com.qartvelo + ads, via the
// git.qartvelo.com custom-domain TXT record), which yields the same group.
val jitpackGroup = System.getenv("GROUP")?.let { group -> "$group.${System.getenv("ARTIFACT")}" }
allprojects {
    group = jitpackGroup ?: "com.qartvelo.ads"
    version = "0.5.0"
}

// Repositories for the published artifacts (configured for every module that publishes):
// - GitHub Packages: https://maven.pkg.github.com/Qartvelo-com/ads
//   `./gradlew publishAllPublicationsToGitHubPackagesRepository` (run by
//   .github/workflows/publish.yml). Credentials come from GITHUB_ACTOR/GITHUB_TOKEN in
//   CI, or gpr.user/gpr.key in ~/.gradle/gradle.properties locally.
// - Staging: a local repository (build/staging-deploy) that publish.yml zips and uploads to
//   Maven Central (Central Portal). Artifacts are signed when SIGNING_KEY (ASCII-armored
//   private key) and SIGNING_PASSWORD are set; Maven Central requires signatures.
subprojects {
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/Qartvelo-com/ads")
                    credentials {
                        username = findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR")
                        password = findProperty("gpr.key") as String? ?: System.getenv("GITHUB_TOKEN")
                    }
                }
                maven {
                    name = "Staging"
                    url = uri(rootProject.layout.buildDirectory.dir("staging-deploy"))
                }
            }
            // POM fields Maven Central requires (name and description are set per module).
            publications.withType<MavenPublication>().configureEach {
                pom {
                    url.set("https://github.com/Qartvelo-com/ads")
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://github.com/Qartvelo-com/ads/blob/main/LICENSE")
                        }
                    }
                    developers {
                        developer {
                            id.set("qartvelo")
                            name.set("Qartvelo Ads")
                            email.set("info@qartvelo.com")
                        }
                    }
                    scm {
                        url.set("https://github.com/Qartvelo-com/ads")
                        connection.set("scm:git:https://github.com/Qartvelo-com/ads.git")
                        developerConnection.set("scm:git:ssh://git@github.com/Qartvelo-com/ads.git")
                    }
                }
            }

            val signingKey = System.getenv("SIGNING_KEY")
            if (!signingKey.isNullOrBlank()) {
                apply(plugin = "signing")
                val publications = publications
                extensions.configure<SigningExtension> {
                    useInMemoryPgpKeys(signingKey, System.getenv("SIGNING_PASSWORD").orEmpty())
                    publications.withType<MavenPublication>().configureEach { sign(this) }
                }
            }
        }
    }
}
