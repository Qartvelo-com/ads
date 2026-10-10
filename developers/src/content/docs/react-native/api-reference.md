---
title: API reference
description: Complete TypeScript API of @qartvelo/react-native-ads 0.6.0.
---

```ts
import {
  QartveloAds, // also the default export
  QartveloAdsBanner,
  QartveloAdsError,
  isQartveloAdsError,
} from '@qartvelo/react-native-ads';
```

## QartveloAds

| Method | Returns | Description |
|---|---|---|
| `initialize(options: QartveloAdsInitOptions)` | `Promise<void>` | Starts the SDK once per process |
| `isInitialized()` | `Promise<boolean>` | First initialization attempt finished |
| `loadInterstitial(placementId)` | `Promise<AdInfo>` | Resolves when an ad is ready, rejects when none |
| `showInterstitial(placementId, options?: ShowOptions)` | `Promise<ShowResult>` | Resolves on dismiss, or `{ shown: false }` when nothing is ready (`loadIfNeeded` waits for a load first) |
| `isInterstitialReady(placementId)` | `Promise<boolean>` | |
| `loadRewarded(placementId)` | `Promise<AdInfo>` | |
| `showRewarded(placementId, options?: ShowOptions)` | `Promise<RewardedShowResult>` | `rewarded` is true only after confirmed completion (`loadIfNeeded` waits for a load first) |
| `isRewardedReady(placementId)` | `Promise<boolean>` | |
| `addListener(type, listener)` | `QartveloAdsSubscription` | Observe events of all placements |
| `removeAllListeners(type?)` | `void` | |
| `setLogLevel(level: LogLevel)` | `void` | No-op where unsupported |
| `setPrivacy(privacy: QartveloAdsPrivacy)` | `void` | No-op where unsupported |
| `isSupported()` | `boolean` | Android or iOS with the native module built in |

## QartveloAdsBanner props

| Prop | Type |
|---|---|
| `placementId` | `string` (required) |
| `size` | `'anchored' \| 'inline'`, default `'anchored'`. `inline` (since 0.6.0) is for banners inside scrolling content: the ad takes the biggest size that fits the width and `maxHeight`, keeping its proportions. See [Inline banners](/react-native/usage/#inline-banners) |
| `maxHeight` | `number`, default `250` (dp on Android, points on iOS, at least 32). Inline only: the most the banner may be tall |
| `style` | `StyleProp<ViewStyle>`; usually `{ width: '100%' }` |
| `onLoaded` | `(e: QartveloAdsEventMap['loaded']) => void` |
| `onLoadFailed` | `(e: QartveloAdsEventMap['loadFailed']) => void` |
| `onShown`, `onImpression`, `onClicked` | `(e) => void` |
| `onFallbackStarted` | `(e: QartveloAdsEventMap['fallbackStarted']) => void` |
| `onNoAdAvailable` | `(e: QartveloAdsEventMap['noAdAvailable']) => void` |
| `onSizeChange` | `(size: { width: number; height: number }) => void` (dp; 0 x 0 when nothing is rendered) |

Other `ViewProps` (such as `testID`) are passed through.

## Types

```ts
type AdFormat = 'banner' | 'interstitial' | 'rewarded';
type AdSource = 'qartvelo' | 'admob';
type LogLevel = 'none' | 'error' | 'info' | 'debug';
type FallbackReason = 'no_fill' | 'timeout' | 'error' | 'creative_failed' | 'disabled';
type SetupIssueCode = 'package_mismatch' | 'platform_mismatch' | 'unknown_placement' | 'format_mismatch';

interface PerPlatform<T> { android?: T; ios?: T }

interface QartveloAdsInitOptions {
  appKey: string | PerPlatform<string>;
  requestTimeoutMs?: number;
  testMode?: boolean;
  testForceNoFill?: boolean;
  testModeInDebugBuilds?: boolean; // default true: test mode in debuggable Android builds
  admobFallback?: boolean;
  admobTestUnitsInDebugBuilds?: boolean; // default true: Google's test units in debuggable Android builds
  logLevel?: LogLevel;
  baseUrl?: string;
  admobAdUnits?: Record<string, string | PerPlatform<string>>;
  preload?: { interstitial?: string[]; rewarded?: string[] };
}

interface ShowOptions { loadIfNeeded?: boolean }

interface AdInfo {
  placementId: string;
  format: AdFormat;
  source: AdSource;
  campaignId?: string; // Qartvelo Ads ads only
  creativeId?: string; // Qartvelo Ads ads only
}

interface Reward { type: string; amount: number }

interface ShowResult { shown: boolean; source?: AdSource }
interface RewardedShowResult extends ShowResult { rewarded: boolean; reward?: Reward }

interface QartveloAdsPrivacy {
  consentGiven?: boolean;
  childDirected?: boolean;
  underAgeOfConsent?: boolean;
}

type QartveloAdsErrorCode =
  | 'not_initialized' | 'invalid_placement' | 'network_error' | 'timeout' | 'no_fill'
  | 'creative_failed' | 'ad_expired' | 'show_failed' | 'already_showing' | 'internal_error'
  | 'unsupported_platform' | 'module_unavailable' | 'invalid_argument';

class QartveloAdsError extends Error {
  readonly code: QartveloAdsErrorCode;
  readonly placementId?: string;
}

interface QartveloAdsSubscription { remove(): void }
```

Event payload types are exported as `QartveloAdsEventMap`, `QartveloAdsEvent`, `QartveloAdsEventType` and `QartveloAdsEventListener<T>`. Besides the ad events, `addListener('setupIssue', ...)` delivers `{ code: SetupIssueCode; message: string; placementId?: string }`, a setup problem to fix (see [Usage](/react-native/usage/#setup-issues) and [Events and errors](/react-native/events-and-errors/#events)).

## Testing with Jest

Mock the package in your own tests:

```ts
jest.mock('@qartvelo/react-native-ads', () => ({
  QartveloAds: {
    initialize: jest.fn().mockResolvedValue(undefined),
    loadInterstitial: jest.fn().mockResolvedValue({ placementId: 'game_end', format: 'interstitial', source: 'qartvelo' }),
    showInterstitial: jest.fn().mockResolvedValue({ shown: true, source: 'qartvelo' }),
    loadRewarded: jest.fn(),
    showRewarded: jest.fn().mockResolvedValue({ shown: true, rewarded: true, reward: { type: 'reward', amount: 1 } }),
    addListener: jest.fn(() => ({ remove: jest.fn() })),
    setPrivacy: jest.fn(),
    setLogLevel: jest.fn(),
  },
  QartveloAdsBanner: () => null,
  isQartveloAdsError: () => false,
}));
```

Without a native module mock, promise calls reject with `module_unavailable`. Mock the module and banner component in JavaScript tests.
