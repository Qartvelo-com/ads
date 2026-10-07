/**
 * Public imperative API. Every call delegates to the Kotlin QartveloAds SDK: campaign selection, timeouts,
 * AdMob fallback, de-duplication of loads and the reward-once guarantee all live natively. This
 * layer only validates arguments, converts wire values into typed results and normalises errors.
 */
import { Platform } from 'react-native';
import { QartveloAdsError, toQartveloAdsError } from './errors';
import { EventRegistry } from './events';
import NativeQartveloAds, { type Spec } from './NativeQartveloAds';
import type {
  AdFormat,
  AdInfo,
  LogLevel,
  QartveloAdsEventListener,
  QartveloAdsEventType,
  QartveloAdsInitOptions,
  QartveloAdsPrivacy,
  QartveloAdsSubscription,
  RewardedShowResult,
  ShowResult,
} from './types';
import {
  toAdInfo,
  toLogLevel,
  toNativeInitOptions,
  toNativePrivacy,
  toPlacementId,
  toRewardedShowResult,
} from './wire';

/** The only platform with a native QartveloAds SDK today. */
const SUPPORTED_OS = 'android';

function availableNative(): Spec | null {
  return Platform.OS === SUPPORTED_OS ? (NativeQartveloAds ?? null) : null;
}

function requireNative(): Spec {
  if (Platform.OS !== SUPPORTED_OS) {
    throw new QartveloAdsError(
      'unsupported_platform',
      `QartveloAds is not available on ${Platform.OS} yet`
    );
  }
  if (!NativeQartveloAds) {
    throw new QartveloAdsError(
      'module_unavailable',
      'The QartveloAds native module is not linked. Rebuild the Android app after installing @qartvelo/react-native-ads.'
    );
  }
  return NativeQartveloAds;
}

/** Runs a native call and turns any failure (including argument errors) into a rejected QartveloAdsError. */
async function run<T>(
  placementId: string | undefined,
  body: (native: Spec) => Promise<T>
): Promise<T> {
  try {
    return await body(requireNative());
  } catch (error) {
    throw toQartveloAdsError(error, placementId);
  }
}

function load(placementId: string, format: AdFormat): Promise<AdInfo> {
  return run(placementId, async (native) => {
    const id = toPlacementId(placementId);
    const info =
      format === 'rewarded'
        ? await native.loadRewarded(id)
        : await native.loadInterstitial(id);
    return toAdInfo(info, id, format);
  });
}

const events = new EventRegistry(availableNative);

export const QartveloAds = {
  /**
   * Starts the SDK once per process; later calls resolve with the first result and ignore new
   * options. A rejection (backend unreachable, app key rejected) does not disable ads: the SDK keeps
   * running on its cached configuration and can still fall back to AdMob.
   */
  initialize(options: QartveloAdsInitOptions): Promise<void> {
    return run(undefined, (native) =>
      native.initializeSdk(toNativeInitOptions(options))
    );
  },

  /** True once the first initialization attempt has finished (successfully or in fallback mode). */
  isInitialized(): Promise<boolean> {
    return run(undefined, (native) => native.isInitialized());
  },

  /** Resolves when an ad (QartveloAds or AdMob fallback) is ready; concurrent loads share one request. */
  loadInterstitial(placementId: string): Promise<AdInfo> {
    return load(placementId, 'interstitial');
  },

  /** Resolves when the ad is dismissed (`shown: true`) or immediately when none is ready (`shown: false`). */
  showInterstitial(placementId: string): Promise<ShowResult> {
    return run(placementId, async (native) => {
      const result = toRewardedShowResult(
        await native.showInterstitial(toPlacementId(placementId))
      );
      return result.source
        ? { shown: result.shown, source: result.source }
        : { shown: result.shown };
    });
  },

  isInterstitialReady(placementId: string): Promise<boolean> {
    return run(placementId, (native) =>
      native.isInterstitialReady(toPlacementId(placementId))
    );
  },

  loadRewarded(placementId: string): Promise<AdInfo> {
    return load(placementId, 'rewarded');
  },

  /**
   * Resolves when the ad is dismissed. `rewarded` is true exactly when the SDK confirmed completion,
   * whichever network served the ad; grant the reward from this result (or the `rewarded` event),
   * not from both.
   */
  showRewarded(placementId: string): Promise<RewardedShowResult> {
    return run(placementId, async (native) =>
      toRewardedShowResult(
        await native.showRewarded(toPlacementId(placementId))
      )
    );
  },

  isRewardedReady(placementId: string): Promise<boolean> {
    return run(placementId, (native) =>
      native.isRewardedReady(toPlacementId(placementId))
    );
  },

  /**
   * Observes SDK events of every placement and format, including banners. On platforms without the
   * SDK the subscription is inert.
   */
  addListener<T extends QartveloAdsEventType>(
    type: T,
    listener: QartveloAdsEventListener<T>
  ): QartveloAdsSubscription {
    return events.add(type, listener);
  },

  /** Removes all listeners of one event type, or all listeners. */
  removeAllListeners(type?: QartveloAdsEventType): void {
    events.removeAll(type);
  },

  /** Changes the native log verbosity at runtime (logcat tag `QartveloAds`). No-op where unsupported. */
  setLogLevel(level: LogLevel): void {
    const value = toLogLevel(level);
    availableNative()?.setLogLevel(value);
  },

  /**
   * Passes the app's privacy signals to the SDK and the AdMob fallback. Omitted fields mean
   * "unknown"; the SDK never assumes consent. No-op where unsupported.
   */
  setPrivacy(privacy: QartveloAdsPrivacy): void {
    const value = toNativePrivacy(privacy);
    availableNative()?.setPrivacy(value);
  },

  /** Whether this platform has a linked QartveloAds SDK (Android with the native module built in). */
  isSupported(): boolean {
    return availableNative() !== null;
  },
} as const;

export type QartveloAdsApi = typeof QartveloAds;
