// swift-tools-version:5.9
// Qartvelo Ads iOS package. Sources/ and Tests/ use the standard Swift Package Manager layout.
// Keep products and dependencies in sync with the repository-root compatibility manifest.
import PackageDescription

let package = Package(
    name: "QartveloAds",
    platforms: [.iOS(.v13)],
    products: [
        .library(name: "QartveloAds", targets: ["QartveloAds"]),
        .library(name: "QartveloAdsAdMob", targets: ["QartveloAdsAdMob"]),
    ],
    dependencies: [
        .package(url: "https://github.com/googleads/swift-package-manager-google-mobile-ads.git", "12.0.0"..<"13.0.0"),
    ],
    targets: [
        .target(
            name: "QartveloAds",
            resources: [.copy("PrivacyInfo.xcprivacy")]
        ),
        .target(
            name: "QartveloAdsAdMob",
            dependencies: [
                "QartveloAds",
                .product(name: "GoogleMobileAds", package: "swift-package-manager-google-mobile-ads"),
            ]
        ),
        .testTarget(
            name: "QartveloAdsTests",
            dependencies: ["QartveloAds"]
        ),
    ]
)
