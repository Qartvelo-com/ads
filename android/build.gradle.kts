plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}

// Shared coordinates for the published SDK artifacts. GitHub Packages and mavenLocal use
// com.qartvelo; JitPack builds set GROUP/ARTIFACT and serve them as
// com.github.Qartvelo-com.qartvelo-ads-sdk.
val jitpackGroup = System.getenv("GROUP")?.let { group -> "$group.${System.getenv("ARTIFACT")}" }
allprojects {
    group = jitpackGroup ?: "com.qartvelo"
    version = "0.2.0"
}

// GitHub Packages: https://maven.pkg.github.com/Qartvelo-com/qartvelo-ads-sdk
// `./gradlew publishAllPublicationsToGitHubPackagesRepository` (run by
// .github/workflows/publish.yml). Credentials come from GITHUB_ACTOR/GITHUB_TOKEN in
// CI, or gpr.user/gpr.key in ~/.gradle/gradle.properties locally.
subprojects {
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/Qartvelo-com/qartvelo-ads-sdk")
                    credentials {
                        username = findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR")
                        password = findProperty("gpr.key") as String? ?: System.getenv("GITHUB_TOKEN")
                    }
                }
            }
        }
    }
}
