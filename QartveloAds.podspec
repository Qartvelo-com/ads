Pod::Spec.new do |s|
  s.name             = 'QartveloAds'
  s.version          = '0.5.1'
  s.summary          = 'Qartvelo Ads iOS SDK: direct-sold ads for Georgian apps, with your own AdMob as fallback.'
  s.description      = <<-DESC
    Serves Qartvelo Ads banner, interstitial and rewarded campaigns. When there is no eligible campaign
    or the request times out, the optional QartveloAdsAdMob pod shows your own AdMob ad unit instead.
  DESC
  s.homepage         = 'https://developers.qartvelo.com/ios/installation/'
  s.license          = { :type => 'MIT', :file => 'LICENSE' }
  s.author           = { 'Qartvelo' => 'info@qartvelo.com' }
  s.source           = { :git => 'https://github.com/Qartvelo-com/ads.git', :tag => s.version.to_s }
  s.ios.deployment_target = '13.0'
  s.swift_versions   = ['5.9']
  s.source_files     = 'ios/Sources/QartveloAds/**/*.swift'
  s.resource_bundles = { 'QartveloAdsPrivacy' => ['ios/Sources/QartveloAds/PrivacyInfo.xcprivacy'] }
  s.frameworks       = 'UIKit', 'AVFoundation', 'ImageIO', 'CryptoKit'
end
