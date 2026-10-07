/**
 * Test double of the QartveloAds TurboModule. Tests install it with
 * `jest.mock('../NativeQartveloAds', () => require('../__fixtures__/fakeNative').nativeModuleMock)`
 * and drive native events through `fake.emit()`.
 */
import { jest } from '@jest/globals';
import type {
  NativeAdEvent,
  NativeAdInfo,
  NativeInitOptions,
  NativePrivacy,
  NativeShowResult,
} from '../NativeQartveloAds';

type Handler = (event: NativeAdEvent) => void;

const handlers = new Set<Handler>();

const native = {
  initializeSdk: jest.fn<(options: NativeInitOptions) => Promise<void>>(),
  isInitialized: jest.fn<() => Promise<boolean>>(),
  loadInterstitial: jest.fn<(placementId: string) => Promise<NativeAdInfo>>(),
  showInterstitial:
    jest.fn<(placementId: string) => Promise<NativeShowResult>>(),
  isInterstitialReady: jest.fn<(placementId: string) => Promise<boolean>>(),
  loadRewarded: jest.fn<(placementId: string) => Promise<NativeAdInfo>>(),
  showRewarded: jest.fn<(placementId: string) => Promise<NativeShowResult>>(),
  isRewardedReady: jest.fn<(placementId: string) => Promise<boolean>>(),
  setLogLevel: jest.fn<(level: string) => void>(),
  setPrivacy: jest.fn<(privacy: NativePrivacy) => void>(),
  onAdEvent: jest.fn((handler: Handler) => {
    handlers.add(handler);
    return { remove: jest.fn(() => handlers.delete(handler)) };
  }),
};

function reset(): void {
  handlers.clear();
  for (const fn of Object.values(native)) {
    fn.mockReset();
  }
  native.onAdEvent.mockImplementation((handler: Handler) => {
    handlers.add(handler);
    return { remove: jest.fn(() => handlers.delete(handler)) };
  });
  native.initializeSdk.mockResolvedValue(undefined);
  native.isInitialized.mockResolvedValue(true);
  native.isInterstitialReady.mockResolvedValue(false);
  native.isRewardedReady.mockResolvedValue(false);
}

reset();

export const fake = {
  native,
  reset,
  /** Delivers one event exactly as the Kotlin module would. */
  emit(event: NativeAdEvent): void {
    [...handlers].forEach((handler) => handler(event));
  },
  /** Native-side subscriptions currently held by the JS layer. */
  subscriberCount(): number {
    return handlers.size;
  },
};

/** Module shape of `NativeQartveloAds.ts` (default export = the TurboModule). */
export const nativeModuleMock = { __esModule: true, default: native };

/** A promise whose settlement the test controls, to observe pending states. */
export function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

/** Error shaped like a React Native promise rejection from `promise.reject(code, message)`. */
export function nativeError(code: string, message: string): Error {
  return Object.assign(new Error(message), { code });
}
