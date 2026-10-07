---
title: API reference
description: Complete TypeScript API of @qartvelo/react-native-ads 0.3.0.
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
| `showInterstitial(placementId)` | `Promise<ShowResult>` | Resolves on dismiss, or `{ shown: false }` when nothing is ready |
| `isInterstitialReady(placementId)` | `Promise<boolean>` | |
| `loadRewarded(placementId)` | `Promise<AdInfo>` | |
| `showRewarded(placementId)` | `Promise<RewardedShowResult>` | `rewarded` is true only after confirmed completion |
| `isRewardedReady(placementId)` | `Promise<boolean>` | |
| `addListener(type, listener)` | `QartveloAdsSubscription` | Observe events of all placements |
| `removeAllListeners(type?)` | `void` | |
| `setLogLevel(level: LogLevel)` | `void` | No-op where unsupported |
| `setPrivacy(privacy: QartveloAdsPrivacy)` | `void` | No-op where unsupported |
| `isSupported()` | `boolean` | Android with the native module built in |

## QartveloAdsBanner props

| Prop | Type |
|---|---|
| `placementId` | `string` (required) |
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

interface QartveloAdsInitOptions {
  appKey: string;
  requestTimeoutMs?: number;
  testMode?: boolean;
  testForceNoFill?: boolean;
  admobFallback?: boolean;
  logLevel?: LogLevel;
  baseUrl?: string;
  admobAdUnits?: Record<string, string>;
}

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

Event payload types are exported as `QartveloAdsEventMap`, `QartveloAdsEvent`, `QartveloAdsEventType` and `QartveloAdsEventListener<T>`.

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

Without a mock it behaves as on iOS (the Jest preset reports iOS): promises reject with `unsupported_platform` and the banner renders nothing.
