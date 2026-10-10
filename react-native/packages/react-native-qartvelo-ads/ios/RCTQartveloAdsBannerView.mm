#import "RCTQartveloAdsBannerView.h"
#import "RNQartveloAds-Swift.h"
#import <react/renderer/components/QartveloAdsSpec/ComponentDescriptors.h>
#import <react/renderer/components/QartveloAdsSpec/EventEmitters.h>
#import <react/renderer/components/QartveloAdsSpec/Props.h>
using namespace facebook::react;

@implementation RCTQartveloAdsBannerView {
  QartveloAdsBannerHost *_host;
}
+ (ComponentDescriptorProvider)componentDescriptorProvider {
  return concreteComponentDescriptorProvider<QartveloAdsBannerViewComponentDescriptor>();
}
- (instancetype)initWithFrame:(CGRect)frame {
  if ((self = [super initWithFrame:frame])) {
    _props = std::make_shared<const QartveloAdsBannerViewProps>();
    _host = [[QartveloAdsBannerHost alloc] initWithFrame:CGRectZero];
    self.contentView = _host;
    __weak RCTQartveloAdsBannerView *weakSelf = self;
    _host.onSize = ^(double width, double height) {
      RCTQartveloAdsBannerView *strongSelf = weakSelf;
      if (strongSelf && strongSelf->_eventEmitter) {
        static_cast<const QartveloAdsBannerViewEventEmitter &>(*strongSelf->_eventEmitter).onSizeChange({width, height});
      }
    };
    _host.onEvent = ^(NSDictionary *data) {
      RCTQartveloAdsBannerView *strongSelf = weakSelf;
      if (!strongSelf || !strongSelf->_eventEmitter) return;
      QartveloAdsBannerViewEventEmitter::OnAdEvent event{};
      event.type = [data[@"type"] ?: @"" UTF8String];
      event.placementId = [data[@"placementId"] ?: @"" UTF8String];
      event.format = [data[@"format"] ?: @"banner" UTF8String];
      event.source = [data[@"source"] ?: @"" UTF8String];
      event.campaignId = [data[@"campaignId"] ?: @"" UTF8String];
      event.creativeId = [data[@"creativeId"] ?: @"" UTF8String];
      event.reason = [data[@"reason"] ?: @"" UTF8String];
      NSDictionary *error = data[@"error"];
      event.errorCode = [error[@"code"] ?: @"" UTF8String];
      event.errorMessage = [error[@"message"] ?: @"" UTF8String];
      static_cast<const QartveloAdsBannerViewEventEmitter &>(*strongSelf->_eventEmitter).onAdEvent(event);
    };
  }
  return self;
}
- (void)updateProps:(Props::Shared const &)props oldProps:(Props::Shared const &)oldProps {
  const auto &next = *std::static_pointer_cast<const QartveloAdsBannerViewProps>(props);
  [_host applyWithPlacement:[NSString stringWithUTF8String:next.placementId.c_str()]
                      inline:next.size == QartveloAdsBannerViewSize::Inline
                   maxHeight:next.maxHeight];
  [super updateProps:props oldProps:oldProps];
}
- (void)prepareForRecycle {
  [_host recycle];
  [super prepareForRecycle];
}
@end
