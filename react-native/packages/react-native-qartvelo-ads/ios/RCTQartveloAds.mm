#import "RCTQartveloAds.h"
#import "RNQartveloAds-Swift.h"

@implementation RCTQartveloAds {
  QartveloAdsBridge *_sdk;
}
RCT_EXPORT_MODULE(QartveloAds)
+ (BOOL)requiresMainQueueSetup { return NO; }
- (instancetype)init {
  if ((self = [super init])) {
    _sdk = [QartveloAdsBridge new];
    __weak RCTQartveloAds *weakSelf = self;
    _sdk.onEvent = ^(NSDictionary *data) {
      RCTQartveloAds *strongSelf = weakSelf;
      if (strongSelf && strongSelf->_eventEmitterCallback) [strongSelf emitOnAdEvent:data];
    };
  }
  return self;
}
- (void)invalidate { [_sdk invalidate]; }
- (void)initializeSdk:(JS::NativeQartveloAds::NativeInitOptions &)options resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject {
  NSMutableDictionary *data = [@{@"appKey": options.appKey()} mutableCopy];
  if (options.requestTimeoutMs()) data[@"requestTimeoutMs"] = @(*options.requestTimeoutMs());
  if (options.testMode()) data[@"testMode"] = @(*options.testMode());
  if (options.testForceNoFill()) data[@"testForceNoFill"] = @(*options.testForceNoFill());
  if (options.admobFallback()) data[@"admobFallback"] = @(*options.admobFallback());
  if (options.logLevel()) data[@"logLevel"] = options.logLevel();
  if (options.baseUrl()) data[@"baseUrl"] = options.baseUrl();
  if (options.admobAdUnits()) data[@"admobAdUnits"] = options.admobAdUnits();
  [_sdk initialize:data resolve:resolve reject:^(NSString *code, NSString *message) { reject(code, message, nil); }];
}
- (void)isInitialized:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject { [_sdk initialized:resolve]; }
- (void)loadInterstitial:(NSString *)placementId resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject {
  [_sdk load:placementId rewarded:NO resolve:resolve reject:^(NSString *code, NSString *message) { reject(code, message, nil); }];
}
- (void)loadRewarded:(NSString *)placementId resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject {
  [_sdk load:placementId rewarded:YES resolve:resolve reject:^(NSString *code, NSString *message) { reject(code, message, nil); }];
}
- (void)showInterstitial:(NSString *)placementId resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject {
  [_sdk show:placementId rewarded:NO resolve:resolve reject:^(NSString *code, NSString *message) { reject(code, message, nil); }];
}
- (void)showRewarded:(NSString *)placementId resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject {
  [_sdk show:placementId rewarded:YES resolve:resolve reject:^(NSString *code, NSString *message) { reject(code, message, nil); }];
}
- (void)isInterstitialReady:(NSString *)placementId resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject { [_sdk ready:placementId rewarded:NO resolve:resolve]; }
- (void)isRewardedReady:(NSString *)placementId resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject { [_sdk ready:placementId rewarded:YES resolve:resolve]; }
- (void)setLogLevel:(NSString *)level { [_sdk setLogLevel:level]; }
- (void)setPrivacy:(JS::NativeQartveloAds::NativePrivacy &)privacy {
  NSMutableDictionary *data = [NSMutableDictionary new];
  if (privacy.consentGiven()) data[@"consentGiven"] = @(*privacy.consentGiven());
  if (privacy.childDirected()) data[@"childDirected"] = @(*privacy.childDirected());
  if (privacy.underAgeOfConsent()) data[@"underAgeOfConsent"] = @(*privacy.underAgeOfConsent());
  [_sdk setPrivacy:data];
}
- (std::shared_ptr<facebook::react::TurboModule>)getTurboModule:(const facebook::react::ObjCTurboModule::InitParams &)params {
  return std::make_shared<facebook::react::NativeQartveloAdsSpecJSI>(params);
}
@end
