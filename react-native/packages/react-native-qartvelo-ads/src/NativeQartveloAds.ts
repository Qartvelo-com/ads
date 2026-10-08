/**
 * Codegen spec of the QartveloAds TurboModule. This is a private wire format between the TypeScript API
 * (`QartveloAds.ts`) and the Kotlin module; app code uses the typed public API instead.
 *
 * Strings that carry enums (format, source, error code, log level) are lower-case on the wire.
 */
import type { CodegenTypes, TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

export type NativeInitOptions = {
  appKey: string;
  requestTimeoutMs?: CodegenTypes.Double;
  testMode?: boolean;
  testForceNoFill?: boolean;
  testModeInDebugBuilds?: boolean;
  admobFallback?: boolean;
  logLevel?: string;
  baseUrl?: string;
  /** Placement code -> AdMob ad unit id. */
  admobAdUnits?: CodegenTypes.UnsafeObject;
};

export type NativeAdInfo = {
  placementId: string;
  format: string;
  source: string;
  campaignId?: string;
  creativeId?: string;
};

export type NativeReward = {
  type: string;
  amount: CodegenTypes.Double;
};

export type NativeShowResult = {
  shown: boolean;
  rewarded: boolean;
  source?: string;
  reward?: NativeReward;
};

export type NativePrivacy = {
  consentGiven?: boolean;
  childDirected?: boolean;
  underAgeOfConsent?: boolean;
};

export type NativeAdError = {
  code: string;
  message: string;
};

/** One SDK lifecycle callback, forwarded from the SDK's global listener. */
export type NativeAdEvent = {
  type: string;
  placementId: string;
  format?: string;
  source?: string;
  campaignId?: string;
  creativeId?: string;
  error?: NativeAdError;
  reason?: string;
  reward?: NativeReward;
};

export interface Spec extends TurboModule {
  initializeSdk(options: NativeInitOptions): Promise<void>;
  isInitialized(): Promise<boolean>;

  loadInterstitial(placementId: string): Promise<NativeAdInfo>;
  showInterstitial(placementId: string): Promise<NativeShowResult>;
  isInterstitialReady(placementId: string): Promise<boolean>;

  loadRewarded(placementId: string): Promise<NativeAdInfo>;
  showRewarded(placementId: string): Promise<NativeShowResult>;
  isRewardedReady(placementId: string): Promise<boolean>;

  setLogLevel(level: string): void;
  setPrivacy(privacy: NativePrivacy): void;

  readonly onAdEvent: CodegenTypes.EventEmitter<NativeAdEvent>;
}

/** `null` where the native module does not exist (iOS, web, or a host that did not autolink it). */
export default TurboModuleRegistry.get<Spec>('QartveloAds');
