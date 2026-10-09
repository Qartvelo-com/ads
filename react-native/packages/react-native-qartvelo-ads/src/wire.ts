/**
 * Conversions between the public types and the native wire format. Pure functions: no native
 * calls, no state.
 */
import { Platform } from 'react-native';
import { QartveloAdsError, toErrorCode } from './errors';
import type {
  NativeAdEvent,
  NativeAdInfo,
  NativeInitOptions,
  NativePrivacy,
  NativeReward,
  NativeShowResult,
} from './NativeQartveloAds';
import type {
  AdFormat,
  AdInfo,
  AdSource,
  FallbackReason,
  LogLevel,
  QartveloAdsEvent,
  QartveloAdsEventMap,
  QartveloAdsEventType,
  QartveloAdsInitOptions,
  QartveloAdsPrivacy,
  Reward,
  RewardedShowResult,
  SetupIssueCode,
} from './types';

const FORMATS: ReadonlySet<string> = new Set<AdFormat>([
  'banner',
  'interstitial',
  'rewarded',
]);
const SOURCES: ReadonlySet<string> = new Set<AdSource>(['qartvelo', 'admob']);
const LOG_LEVELS: ReadonlySet<string> = new Set<LogLevel>([
  'none',
  'error',
  'info',
  'debug',
]);
const FALLBACK_REASONS: ReadonlySet<string> = new Set<FallbackReason>([
  'no_fill',
  'timeout',
  'error',
  'creative_failed',
  'disabled',
]);

export const EVENT_TYPES: readonly QartveloAdsEventType[] = [
  'loaded',
  'loadFailed',
  'shown',
  'impression',
  'clicked',
  'dismissed',
  'rewarded',
  'fallbackStarted',
  'noAdAvailable',
  'setupIssue',
];
const EVENT_TYPE_SET: ReadonlySet<string> = new Set(EVENT_TYPES);

export function isEventType(value: unknown): value is QartveloAdsEventType {
  return typeof value === 'string' && EVENT_TYPE_SET.has(value);
}

function toFormat(value: unknown): AdFormat | undefined {
  const lower = typeof value === 'string' ? value.toLowerCase() : '';
  return FORMATS.has(lower) ? (lower as AdFormat) : undefined;
}

function toSource(value: unknown): AdSource | undefined {
  const lower = typeof value === 'string' ? value.toLowerCase() : '';
  return SOURCES.has(lower) ? (lower as AdSource) : undefined;
}

function nonEmpty(value: unknown): string | undefined {
  return typeof value === 'string' && value.length > 0 ? value : undefined;
}

function toReward(value: NativeReward | null | undefined): Reward | undefined {
  if (!value || typeof value.amount !== 'number') {
    return undefined;
  }
  return { type: nonEmpty(value.type) ?? 'reward', amount: value.amount };
}

// ---- arguments ---------------------------------------------------------------------------------

function invalid(message: string, placementId?: string): QartveloAdsError {
  return new QartveloAdsError('invalid_argument', message, placementId);
}

export function toPlacementId(placementId: unknown): string {
  const id = typeof placementId === 'string' ? placementId.trim() : '';
  if (!id) {
    throw invalid('placementId must be a non-empty string');
  }
  return id;
}

export function toLogLevel(level: unknown): LogLevel {
  if (typeof level !== 'string' || !LOG_LEVELS.has(level)) {
    throw invalid(`logLevel must be one of: ${[...LOG_LEVELS].join(', ')}`);
  }
  return level as LogLevel;
}

function optionalBoolean(
  options: object,
  key: keyof QartveloAdsInitOptions | keyof QartveloAdsPrivacy
): boolean | undefined {
  const value = (options as Record<string, unknown>)[key];
  if (value === undefined || value === null) {
    return undefined;
  }
  if (typeof value !== 'boolean') {
    throw invalid(`${key} must be a boolean`);
  }
  return value;
}

/** The value for the running platform, from a string or a `{ android, ios }` object. */
function platformValue(value: unknown, name: string): string | undefined {
  if (typeof value === 'string') {
    return value.trim() || undefined;
  }
  if (typeof value === 'object' && value !== null && !Array.isArray(value)) {
    const own = (value as Record<string, unknown>)[Platform.OS];
    if (own === undefined || own === null) {
      return undefined;
    }
    if (typeof own !== 'string') {
      throw invalid(`${name}.${Platform.OS} must be a string`);
    }
    return own.trim() || undefined;
  }
  throw invalid(
    `${name} must be a string or an object with android and ios values`
  );
}

/** Validates the public options and drops unset keys so native defaults apply. */
export function toNativeInitOptions(
  options: QartveloAdsInitOptions
): NativeInitOptions {
  if (typeof options !== 'object' || options === null) {
    throw invalid('initialize() expects an options object');
  }
  const appKey = platformValue(options.appKey, 'appKey');
  if (!appKey) {
    throw invalid(
      typeof options.appKey === 'object' && options.appKey !== null
        ? `appKey has no value for ${Platform.OS}`
        : 'appKey must be a non-empty string'
    );
  }
  const result: NativeInitOptions = { appKey };

  const timeout = options.requestTimeoutMs;
  if (timeout !== undefined && timeout !== null) {
    if (
      typeof timeout !== 'number' ||
      !Number.isFinite(timeout) ||
      timeout <= 0
    ) {
      throw invalid(
        'requestTimeoutMs must be a positive number of milliseconds'
      );
    }
    result.requestTimeoutMs = Math.round(timeout);
  }

  const testMode = optionalBoolean(options, 'testMode');
  if (testMode !== undefined) {
    result.testMode = testMode;
  }
  const testForceNoFill = optionalBoolean(options, 'testForceNoFill');
  if (testForceNoFill !== undefined) {
    result.testForceNoFill = testForceNoFill;
  }
  const testModeInDebugBuilds = optionalBoolean(
    options,
    'testModeInDebugBuilds'
  );
  if (testModeInDebugBuilds !== undefined) {
    result.testModeInDebugBuilds = testModeInDebugBuilds;
  }
  const admobFallback = optionalBoolean(options, 'admobFallback');
  if (admobFallback !== undefined) {
    result.admobFallback = admobFallback;
  }
  const admobTestUnits = optionalBoolean(
    options,
    'admobTestUnitsInDebugBuilds'
  );
  if (admobTestUnits !== undefined) {
    result.admobTestUnitsInDebugBuilds = admobTestUnits;
  }

  if (options.logLevel !== undefined && options.logLevel !== null) {
    result.logLevel = toLogLevel(options.logLevel);
  }

  if (options.baseUrl !== undefined && options.baseUrl !== null) {
    const baseUrl =
      typeof options.baseUrl === 'string' ? options.baseUrl.trim() : '';
    if (!/^https?:\/\/\S+$/i.test(baseUrl)) {
      throw invalid('baseUrl must be an http(s) URL');
    }
    result.baseUrl = baseUrl;
  }

  if (options.admobAdUnits !== undefined && options.admobAdUnits !== null) {
    const units = options.admobAdUnits;
    if (typeof units !== 'object' || Array.isArray(units)) {
      throw invalid('admobAdUnits must map placement codes to ad unit ids');
    }
    const copy: Record<string, string> = {};
    for (const [code, unit] of Object.entries(units)) {
      const id = platformValue(unit, `admobAdUnits["${code}"]`);
      if (!code.trim() || (typeof unit === 'string' && !id)) {
        throw invalid(`admobAdUnits["${code}"] must be a non-empty ad unit id`);
      }
      if (id) {
        copy[code.trim()] = id;
      }
    }
    result.admobAdUnits = copy;
  }
  return result;
}

export function toNativePrivacy(privacy: QartveloAdsPrivacy): NativePrivacy {
  if (typeof privacy !== 'object' || privacy === null) {
    throw invalid('setPrivacy() expects an object');
  }
  const result: NativePrivacy = {};
  const consentGiven = optionalBoolean(privacy, 'consentGiven');
  if (consentGiven !== undefined) {
    result.consentGiven = consentGiven;
  }
  const childDirected = optionalBoolean(privacy, 'childDirected');
  if (childDirected !== undefined) {
    result.childDirected = childDirected;
  }
  const underAge = optionalBoolean(privacy, 'underAgeOfConsent');
  if (underAge !== undefined) {
    result.underAgeOfConsent = underAge;
  }
  return result;
}

export interface PreloadPlan {
  interstitial: string[];
  rewarded: string[];
}

/** Validates `preload`, trimming and de-duplicating the placement codes. */
export function toPreload(preload: unknown): PreloadPlan {
  const plan: PreloadPlan = { interstitial: [], rewarded: [] };
  if (preload === undefined || preload === null) {
    return plan;
  }
  if (typeof preload !== 'object' || Array.isArray(preload)) {
    throw invalid(
      'preload must be an object with interstitial and rewarded arrays'
    );
  }
  for (const format of ['interstitial', 'rewarded'] as const) {
    const codes = (preload as Record<string, unknown>)[format];
    if (codes === undefined || codes === null) {
      continue;
    }
    if (!Array.isArray(codes)) {
      throw invalid(`preload.${format} must be an array of placement codes`);
    }
    for (const code of codes) {
      const id = typeof code === 'string' ? code.trim() : '';
      if (!id) {
        throw invalid(
          `preload.${format} must only contain non-empty placement codes`
        );
      }
      if (!plan[format].includes(id)) {
        plan[format].push(id);
      }
    }
  }
  return plan;
}

/** Validates the options of `showInterstitial()` and `showRewarded()`. */
export function toShowOptions(options: unknown): { loadIfNeeded: boolean } {
  if (options === undefined || options === null) {
    return { loadIfNeeded: false };
  }
  if (typeof options !== 'object' || Array.isArray(options)) {
    throw invalid('show options must be an object');
  }
  const value = (options as Record<string, unknown>).loadIfNeeded;
  if (value === undefined || value === null) {
    return { loadIfNeeded: false };
  }
  if (typeof value !== 'boolean') {
    throw invalid('loadIfNeeded must be a boolean');
  }
  return { loadIfNeeded: value };
}

// ---- results -----------------------------------------------------------------------------------

export function toAdInfo(
  value: NativeAdInfo,
  placementId: string,
  format: AdFormat
): AdInfo {
  const info: AdInfo = {
    placementId: nonEmpty(value?.placementId) ?? placementId,
    format: toFormat(value?.format) ?? format,
    source: toSource(value?.source) ?? 'qartvelo',
  };
  const campaignId = nonEmpty(value?.campaignId);
  if (campaignId) {
    info.campaignId = campaignId;
  }
  const creativeId = nonEmpty(value?.creativeId);
  if (creativeId) {
    info.creativeId = creativeId;
  }
  return info;
}

export function toRewardedShowResult(
  value: NativeShowResult | null | undefined
): RewardedShowResult {
  const shown = value?.shown === true;
  const result: RewardedShowResult = { shown, rewarded: false };
  const source = toSource(value?.source);
  if (shown && source) {
    result.source = source;
  }
  const reward = toReward(value?.reward);
  if (shown && value?.rewarded === true && reward) {
    result.rewarded = true;
    result.reward = reward;
  }
  return result;
}

// ---- events ------------------------------------------------------------------------------------

const SETUP_ISSUE_CODES: ReadonlySet<string> = new Set<SetupIssueCode>([
  'package_mismatch',
  'platform_mismatch',
  'unknown_placement',
  'format_mismatch',
]);

function toSetupIssue(
  raw: NativeAdEvent
): QartveloAdsEventMap['setupIssue'] | null {
  const code = nonEmpty(raw.error?.code);
  const message = nonEmpty(raw.error?.message);
  if (!code || !SETUP_ISSUE_CODES.has(code) || !message) {
    return null;
  }
  const placementId = nonEmpty(raw.placementId);
  const event: QartveloAdsEventMap['setupIssue'] = {
    type: 'setupIssue',
    code: code as SetupIssueCode,
    message,
  };
  return placementId ? { ...event, placementId } : event;
}

/**
 * Builds a typed public event from a native payload. Returns null for payloads that do not match
 * the contract (unknown type, missing placement, missing format/source where required), so
 * listeners never see half-formed events.
 */
export function toEvent(
  raw: NativeAdEvent | null | undefined
): QartveloAdsEvent | null {
  if (!raw || !isEventType(raw.type)) {
    return null;
  }
  if (raw.type === 'setupIssue') {
    return toSetupIssue(raw);
  }
  const placementId = nonEmpty(raw.placementId);
  if (!placementId) {
    return null;
  }
  const format = toFormat(raw.format);

  switch (raw.type) {
    case 'loadFailed': {
      const event: QartveloAdsEvent = {
        type: 'loadFailed',
        placementId,
        error: {
          code: toErrorCode(raw.error?.code),
          message: raw.error?.message ?? '',
        },
      };
      if (format) {
        event.format = format;
      }
      return event;
    }
    case 'fallbackStarted': {
      if (!format) {
        return null;
      }
      const reason = (raw.reason ?? '').toLowerCase();
      return {
        type: 'fallbackStarted',
        placementId,
        format,
        reason: FALLBACK_REASONS.has(reason)
          ? (reason as FallbackReason)
          : 'error',
      };
    }
    case 'noAdAvailable':
      return format ? { type: 'noAdAvailable', placementId, format } : null;
    default: {
      const source = toSource(raw.source);
      if (!format || !source) {
        return null;
      }
      const base: AdInfo = { placementId, format, source };
      const campaignId = nonEmpty(raw.campaignId);
      if (campaignId) {
        base.campaignId = campaignId;
      }
      const creativeId = nonEmpty(raw.creativeId);
      if (creativeId) {
        base.creativeId = creativeId;
      }
      if (raw.type === 'rewarded') {
        const reward = toReward(raw.reward);
        return reward ? { ...base, type: 'rewarded', reward } : null;
      }
      return { ...base, type: raw.type };
    }
  }
}
