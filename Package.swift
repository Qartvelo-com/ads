// swift-tools-version:5.9
// GitHub Swift Package Manager compatibility entry point.
// The primary iOS package is ios/Package.swift; keep products and dependencies in sync with it.
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
            path: "ios/Sources/QartveloAds",
            resources: [.copy("PrivacyInfo.xcprivacy")]
        ),
        .target(
            name: "QartveloAdsAdMob",
            dependencies: [
                "QartveloAds",
                .product(name: "GoogleMobileAds", package: "swift-package-manager-google-mobile-ads"),
            ],
            path: "ios/Sources/QartveloAdsAdMob"
        ),
        .testTarget(
            name: "QartveloAdsTests",
            dependencies: ["QartveloAds"],
            path: "ios/Tests/QartveloAdsTests"
        ),
    ]
)
