/**
 * Public, platform-neutral types of `@qartvelo/react-native-ads`. Shared by Android and iOS.
 */

export type AdFormat = 'banner' | 'interstitial' | 'rewarded';

/** Network that delivered the ad: QartveloAds direct campaigns, or the publisher's AdMob fallback. */
export type AdSource = 'qartvelo' | 'admob';

export type LogLevel = 'none' | 'error' | 'info' | 'debug';

/** Why QartveloAds handed a request over to the fallback network. */
export type FallbackReason =
  'no_fill' | 'timeout' | 'error' | 'creative_failed' | 'disabled';

export type QartveloAdsErrorCode =
  /** `initialize()` was not called (or failed before the SDK could start). */
  | 'not_initialized'
  /** Unknown placement code, or a placement used with the wrong format. */
  | 'invalid_placement'
  | 'network_error'
  | 'timeout'
  | 'no_fill'
  | 'creative_failed'
  | 'ad_expired'
  | 'show_failed'
  /** Another full-screen ad is already on screen. */
  | 'already_showing'
  | 'internal_error'
  /** The current platform has no QartveloAds SDK yet (web). */
  | 'unsupported_platform'
  /** The native module is missing: the app was not rebuilt after installing the package. */
  | 'module_unavailable'
  /** A JavaScript argument was rejected before reaching native code. */
  | 'invalid_argument';

export interface QartveloAdsInitOptions {
  /** Publisher app key from the QartveloAds dashboard (`app_...`). Never the server-side secret. */
  appKey: string;
  /** QartveloAds request budget before falling back to AdMob. A per-placement server value wins. */
  requestTimeoutMs?: number;
  /**
   * Serve test ads: real ads labelled "Test ad" (or the built-in test ad), never billed. AdMob
   * uses Google's test units. Also on automatically in Android debug builds. On iOS, Simulator and all non-App Store
   * installs are always non-billable, regardless of the test flags.
   */
  testMode?: boolean;
  /** In test mode, make QartveloAds answer "no fill" so the AdMob fallback can be exercised. */
  testForceNoFill?: boolean;
  /**
   * Turn test mode on automatically in debuggable (developer) Android builds, like AdMob test
   * devices. Release builds are unaffected. Default true; set false to see exactly what a release
   * build does. Ignored on iOS: all non-App Store installs always use test mode.
   */
  testModeInDebugBuilds?: boolean;
  /** Allow the AdMob fallback (requires the optional native AdMob adapter). Default true. */
  admobFallback?: boolean;
  logLevel?: LogLevel;
  /** QartveloAds API base URL, e.g. `https://api.example.com/`. */
  baseUrl?: string;
  /** Placement code -> your AdMob ad unit id. Overrides the unit configured in the dashboard. */
  admobAdUnits?: Record<string, string>;
}

export interface AdInfo {
  placementId: string;
  format: AdFormat;
  source: AdSource;
  /** Set only for QartveloAds ads. */
  campaignId?: string;
  /** Set only for QartveloAds ads. */
  creativeId?: string;
}

export interface Reward {
  type: string;
  amount: number;
}

/** Result of `showInterstitial()`, resolved when the ad is dismissed or could not be shown. */
export interface ShowResult {
  /** False when no ad was available to show. */
  shown: boolean;
  source?: AdSource;
}

/** Result of `showRewarded()`. `rewarded` is true only after a confirmed completion. */
export interface RewardedShowResult extends ShowResult {
  rewarded: boolean;
  reward?: Reward;
}

/**
 * Privacy signals supplied by the app. `undefined` means unknown: QartveloAds never assumes consent and
 * never collects consent on the app's behalf.
 */
export interface QartveloAdsPrivacy {
  consentGiven?: boolean;
  childDirected?: boolean;
  underAgeOfConsent?: boolean;
}

export interface QartveloAdsErrorInfo {
  code: QartveloAdsErrorCode;
  message: string;
}

interface EventBase {
  placementId: string;
  format: AdFormat;
}

interface AdEventBase extends EventBase {
  source: AdSource;
  campaignId?: string;
  creativeId?: string;
}

/** Payload of each event type delivered by `QartveloAds.addListener()`. */
export interface QartveloAdsEventMap {
  loaded: AdEventBase & { type: 'loaded' };
  /** `format` is omitted only when the SDK reports a failure for a placement this app never loaded. */
  loadFailed: Omit<EventBase, 'format'> & {
    type: 'loadFailed';
    format?: AdFormat;
    error: QartveloAdsErrorInfo;
  };
  shown: AdEventBase & { type: 'shown' };
  impression: AdEventBase & { type: 'impression' };
  clicked: AdEventBase & { type: 'clicked' };
  dismissed: AdEventBase & { type: 'dismissed' };
  rewarded: AdEventBase & { type: 'rewarded'; reward: Reward };
  fallbackStarted: EventBase & {
    type: 'fallbackStarted';
    reason: FallbackReason;
  };
  noAdAvailable: EventBase & { type: 'noAdAvailable' };
}

export type QartveloAdsEventType = keyof QartveloAdsEventMap;

export type QartveloAdsEvent = QartveloAdsEventMap[QartveloAdsEventType];

export type QartveloAdsEventListener<T extends QartveloAdsEventType> = (
  event: QartveloAdsEventMap[T]
) => void;

export interface QartveloAdsSubscription {
  /** Stops delivery to this listener. Safe to call more than once. */
  remove(): void;
}
