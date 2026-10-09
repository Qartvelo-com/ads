require 'json'
package = JSON.parse(File.read(File.join(__dir__, 'package.json')))

# The AdMob fallback adapter is linked when the app's app.json has
# "@qartvelo/react-native-ads": { "admob": { ... } } (bare React Native), or with
# ENV['QARTVELO_ADS_ADMOB_ENABLED'] = 'true' in the Podfile (written by the Expo config plugin, or set
# by hand in 0.4.x setups).
qartvelo_app_json_admob = begin
  path = File.join(Pod::Config.instance.installation_root.to_s, '..', 'app.json')
  json = File.file?(path) ? JSON.parse(File.read(path)) : nil
  config = json.is_a?(Hash) ? json['@qartvelo/react-native-ads'] : nil
  config.is_a?(Hash) ? config['admob'] : nil
rescue StandardError
  nil
end

qartvelo_admob_enabled = ENV['QARTVELO_ADS_ADMOB_ENABLED'] == 'true' || !qartvelo_app_json_admob.nil?

Pod::Spec.new do |s|
  s.name = 'RNQartveloAds'
  s.version = package['version']
  s.summary = package['description']
  s.homepage = 'https://developers.qartvelo.com/react-native/installation/'
  s.license = { :type => 'MIT', :file => 'ios/sdk/LICENSE' }
  s.author = { 'Qartvelo' => 'info@qartvelo.com' }
  s.source = { :git => 'https://github.com/Qartvelo-com/ads.git', :tag => s.version.to_s }
  s.platforms = { :ios => min_ios_version_supported }
  s.swift_version = '5.9'
  s.module_name = 'RNQartveloAds'
  s.source_files = 'ios/*.{h,mm,swift}', 'ios/sdk/QartveloAds/**/*.swift'
  s.private_header_files = 'ios/*.h'
  s.resource_bundles = { 'QartveloAdsPrivacy' => ['ios/sdk/QartveloAds/PrivacyInfo.xcprivacy'] }
  s.frameworks = 'UIKit', 'AVFoundation', 'ImageIO', 'CryptoKit'
  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES' }
  if qartvelo_admob_enabled
    s.source_files = 'ios/*.{h,mm,swift}', 'ios/sdk/{QartveloAds,QartveloAdsAdMob}/**/*.swift'
    s.dependency 'Google-Mobile-Ads-SDK', '~> 12.0'
  end
  install_modules_dependencies(s)
end
