import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import {
  Platform,
  StyleSheet,
  type NativeSyntheticEvent,
  type StyleProp,
  type ViewProps,
  type ViewStyle,
} from 'react-native';
import QartveloAdsBannerView, {
  type NativeBannerAdEvent,
  type NativeBannerSizeEvent,
} from './QartveloAdsBannerViewNativeComponent';
import type { QartveloAdsEventMap } from './types';
import { toEvent } from './wire';

export interface BannerSize {
  /** dp */
  width: number;
  /** dp */
  height: number;
}

export interface QartveloAdsBannerProps extends Omit<
  ViewProps,
  'style' | 'children'
> {
  /** Placement code from the QartveloAds dashboard. Changing it loads the new placement. */
  placementId: string;
  /**
   * Usually `{ width: '100%' }`. The height follows the rendered creative unless you set one.
   * The banner is collapsed (height 0) until an ad is rendered.
   */
  style?: StyleProp<ViewStyle>;
  onLoaded?: (event: QartveloAdsEventMap['loaded']) => void;
  onLoadFailed?: (event: QartveloAdsEventMap['loadFailed']) => void;
  onShown?: (event: QartveloAdsEventMap['shown']) => void;
  onImpression?: (event: QartveloAdsEventMap['impression']) => void;
  onClicked?: (event: QartveloAdsEventMap['clicked']) => void;
  onFallbackStarted?: (event: QartveloAdsEventMap['fallbackStarted']) => void;
  onNoAdAvailable?: (event: QartveloAdsEventMap['noAdAvailable']) => void;
  /** The natural size of the rendered ad changed (0 x 0 when nothing is rendered). */
  onSizeChange?: (size: BannerSize) => void;
}

type Callbacks = Pick<
  QartveloAdsBannerProps,
  | 'onLoaded'
  | 'onLoadFailed'
  | 'onShown'
  | 'onImpression'
  | 'onClicked'
  | 'onFallbackStarted'
  | 'onNoAdAvailable'
  | 'onSizeChange'
>;

function toPublicEvent(raw: NativeBannerAdEvent) {
  return toEvent({
    type: raw.type,
    placementId: raw.placementId,
    format: raw.format,
    source: raw.source,
    campaignId: raw.campaignId,
    creativeId: raw.creativeId,
    reason: raw.reason,
    error: raw.errorCode
      ? { code: raw.errorCode, message: raw.errorMessage ?? '' }
      : undefined,
  });
}

function deliver(callbacks: Callbacks, raw: NativeBannerAdEvent): void {
  const event = toPublicEvent(raw);
  if (!event) {
    return;
  }
  switch (event.type) {
    case 'loaded':
      callbacks.onLoaded?.(event);
      break;
    case 'loadFailed':
      callbacks.onLoadFailed?.(event);
      break;
    case 'shown':
      callbacks.onShown?.(event);
      break;
    case 'impression':
      callbacks.onImpression?.(event);
      break;
    case 'clicked':
      callbacks.onClicked?.(event);
      break;
    case 'fallbackStarted':
      callbacks.onFallbackStarted?.(event);
      break;
    case 'noAdAvailable':
      callbacks.onNoAdAvailable?.(event);
      break;
    default:
      // dismissed / rewarded never apply to banners.
      break;
  }
}

/**
 * Android banner. Re-renders never reach the SDK: the native props are only `placementId`, style
 * and two event handlers whose identity is stable for the component's lifetime, so new inline
 * callbacks from the parent cause no native updates. Remounting reuses the SDK's cached banner for
 * the placement instead of requesting a new one.
 */
function NativeBanner({
  placementId,
  style,
  onLoaded,
  onLoadFailed,
  onShown,
  onImpression,
  onClicked,
  onFallbackStarted,
  onNoAdAvailable,
  onSizeChange,
  ...viewProps
}: QartveloAdsBannerProps) {
  // Latest callbacks, read at event time, so handlers passed to native never change identity.
  const callbacks = useRef<Callbacks>({});
  useLayoutEffect(() => {
    callbacks.current = {
      onLoaded,
      onLoadFailed,
      onShown,
      onImpression,
      onClicked,
      onFallbackStarted,
      onNoAdAvailable,
      onSizeChange,
    };
  });

  const [contentHeight, setContentHeight] = useState(0);

  const handleAdEvent = useCallback(
    (event: NativeSyntheticEvent<NativeBannerAdEvent>) => {
      deliver(callbacks.current, event.nativeEvent);
    },
    []
  );

  const handleSizeChange = useCallback(
    (event: NativeSyntheticEvent<NativeBannerSizeEvent>) => {
      const { width, height } = event.nativeEvent;
      setContentHeight(height);
      callbacks.current.onSizeChange?.({ width, height });
    },
    []
  );

  const nativeStyle = useMemo(
    () => [styles.banner, { height: contentHeight }, style],
    [contentHeight, style]
  );

  return (
    <QartveloAdsBannerView
      {...viewProps}
      placementId={placementId}
      style={nativeStyle}
      onAdEvent={handleAdEvent}
      onSizeChange={handleSizeChange}
    />
  );
}

/** Platforms without an QartveloAds SDK render nothing and report `unsupported_platform` once. */
function UnsupportedBanner({
  placementId,
  onLoadFailed,
}: QartveloAdsBannerProps) {
  const onLoadFailedRef = useRef(onLoadFailed);
  useLayoutEffect(() => {
    onLoadFailedRef.current = onLoadFailed;
  });
  useEffect(() => {
    onLoadFailedRef.current?.({
      type: 'loadFailed',
      placementId,
      format: 'banner',
      error: {
        code: 'unsupported_platform',
        message: `QartveloAds banners are not available on ${Platform.OS} yet`,
      },
    });
  }, [placementId]);
  return null;
}

export function QartveloAdsBanner(props: QartveloAdsBannerProps) {
  return Platform.OS === 'android' ? (
    <NativeBanner {...props} />
  ) : (
    <UnsupportedBanner {...props} />
  );
}

const styles = StyleSheet.create({
  banner: {
    width: '100%',
    overflow: 'hidden',
  },
});
