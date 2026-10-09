Pod::Spec.new do |s|
  s.name             = 'QartveloAdsAdMob'
  s.version          = '0.5.1'
  s.summary          = 'AdMob fallback for the Qartvelo Ads iOS SDK, using your own AdMob app and ad units.'
  s.homepage         = 'https://developers.qartvelo.com/guides/admob-fallback/'
  s.license          = { :type => 'MIT', :file => 'LICENSE' }
  s.author           = { 'Qartvelo' => 'info@qartvelo.com' }
  s.source           = { :git => 'https://github.com/Qartvelo-com/ads.git', :tag => s.version.to_s }
  s.ios.deployment_target = '13.0'
  s.swift_versions   = ['5.9']
  s.static_framework = true
  s.source_files     = 'ios/Sources/QartveloAdsAdMob/**/*.swift'
  s.dependency 'QartveloAds', s.version.to_s
  s.dependency 'Google-Mobile-Ads-SDK', '~> 12.0'
end
