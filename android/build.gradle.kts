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
    version = "0.3.3"
}

// GitHub Packages: https://maven.pkg.github.com/Qartvelo-com/ads
// `./gradlew publishAllPublicationsToGitHubPackagesRepository` (run by
// .github/workflows/publish.yml). Credentials come from GITHUB_ACTOR/GITHUB_TOKEN in
// CI, or gpr.user/gpr.key in ~/.gradle/gradle.properties locally.
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
            }
        }
    }
}
