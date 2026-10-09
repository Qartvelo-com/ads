/**
 * Public imperative API. Every call delegates to the native QartveloAds SDK: campaign selection, timeouts,
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
  QartveloAdsEventMap,
  QartveloAdsEventType,
  QartveloAdsInitOptions,
  QartveloAdsPrivacy,
  QartveloAdsSubscription,
  RewardedShowResult,
  ShowOptions,
  ShowResult,
} from './types';
import {
  toAdInfo,
  toLogLevel,
  toNativeInitOptions,
  toNativePrivacy,
  toPlacementId,
  toPreload,
  toRewardedShowResult,
  toShowOptions,
} from './wire';

function supportedPlatform(): boolean {
  return Platform.OS === 'android' || Platform.OS === 'ios';
}

function availableNative(): Spec | null {
  return supportedPlatform() ? (NativeQartveloAds ?? null) : null;
}

function requireNative(): Spec {
  if (!supportedPlatform()) {
    throw new QartveloAdsError(
      'unsupported_platform',
      `QartveloAds is not available on ${Platform.OS} yet`
    );
  }
  if (!NativeQartveloAds) {
    throw new QartveloAdsError(
      'module_unavailable',
      'The QartveloAds native module is not linked. Rebuild the native app after installing @qartvelo/react-native-ads.'
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

type FullscreenFormat = 'interstitial' | 'rewarded';

/** Placements from `initialize({ preload })`, reloaded after every show. */
const preloaded: Record<FullscreenFormat, Set<string>> = {
  interstitial: new Set(),
  rewarded: new Set(),
};

function preload(placementId: string, format: FullscreenFormat): void {
  load(placementId, format).catch(() => {
    // A failed preload only means the next show loads on demand or finds nothing.
  });
}

function reloadIfPreloaded(
  placementId: unknown,
  format: FullscreenFormat
): void {
  const id = typeof placementId === 'string' ? placementId.trim() : '';
  if (id && preloaded[format].has(id)) {
    preload(id, format);
  }
}

/** Shows a full-screen ad; with `loadIfNeeded`, waits for a load first when none is ready. */
async function show(
  placementId: string,
  format: FullscreenFormat,
  options: ShowOptions | undefined
): Promise<RewardedShowResult> {
  try {
    return await run(placementId, async (native) => {
      const id = toPlacementId(placementId);
      const { loadIfNeeded } = toShowOptions(options);
      if (loadIfNeeded) {
        const ready =
          format === 'rewarded'
            ? await native.isRewardedReady(id)
            : await native.isInterstitialReady(id);
        if (!ready) {
          try {
            await (format === 'rewarded'
              ? native.loadRewarded(id)
              : native.loadInterstitial(id));
          } catch {
            return { shown: false, rewarded: false };
          }
        }
      }
      return toRewardedShowResult(
        format === 'rewarded'
          ? await native.showRewarded(id)
          : await native.showInterstitial(id)
      );
    });
  } finally {
    reloadIfPreloaded(placementId, format);
  }
}

const warnedSetupIssues = new Set<string>();

/** Development builds print each setup issue once, so it shows in LogBox without app code. */
function warnSetupIssue(issue: QartveloAdsEventMap['setupIssue']): void {
  if (!__DEV__) {
    return;
  }
  const key = `${issue.code}|${issue.placementId ?? ''}`;
  if (warnedSetupIssues.has(key)) {
    return;
  }
  warnedSetupIssues.add(key);
  console.warn(`[QartveloAds] ${issue.message}`);
}

const events = new EventRegistry(availableNative, {
  onSetupIssue: warnSetupIssue,
});

export const QartveloAds = {
  /**
   * Starts the SDK once per process; later calls resolve with the first result and ignore new
   * options. A rejection (backend unreachable, app key rejected) does not disable ads: the SDK keeps
   * running on its cached configuration and can still fall back to AdMob.
   */
  initialize(options: QartveloAdsInitOptions): Promise<void> {
    return run(undefined, async (native) => {
      const nativeOptions = toNativeInitOptions(options);
      const plan = toPreload(options.preload);
      if (__DEV__) {
        events.keepNativeSubscription();
      }
      const started = native.initializeSdk(nativeOptions);
      for (const format of ['interstitial', 'rewarded'] as const) {
        for (const id of plan[format]) {
          preloaded[format].add(id);
          preload(id, format);
        }
      }
      await started;
    });
  },

  /** True once the first initialization attempt has finished (successfully or in fallback mode). */
  isInitialized(): Promise<boolean> {
    return run(undefined, (native) => native.isInitialized());
  },

  /** Resolves when an ad (QartveloAds or AdMob fallback) is ready; concurrent loads share one request. */
  loadInterstitial(placementId: string): Promise<AdInfo> {
    return load(placementId, 'interstitial');
  },

  /**
   * Resolves when the ad is dismissed (`shown: true`) or immediately when none is ready (`shown: false`).
   * With `{ loadIfNeeded: true }`, waits for a load when no ad is ready.
   */
  showInterstitial(
    placementId: string,
    options?: ShowOptions
  ): Promise<ShowResult> {
    return show(placementId, 'interstitial', options).then((result) =>
      result.source
        ? { shown: result.shown, source: result.source }
        : { shown: result.shown }
    );
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
   * not from both. With `{ loadIfNeeded: true }`, waits for a load when no ad is ready.
   */
  showRewarded(
    placementId: string,
    options?: ShowOptions
  ): Promise<RewardedShowResult> {
    return show(placementId, 'rewarded', options);
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

  /** Whether this platform has a linked QartveloAds SDK (Android or iOS with the native module built in). */
  isSupported(): boolean {
    return availableNative() !== null;
  },
} as const;

export type QartveloAdsApi = typeof QartveloAds;
