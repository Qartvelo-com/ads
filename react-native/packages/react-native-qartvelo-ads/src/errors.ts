import type { QartveloAdsErrorCode, QartveloAdsErrorInfo } from './types';

const ERROR_CODES: ReadonlySet<string> = new Set<QartveloAdsErrorCode>([
  'not_initialized',
  'invalid_placement',
  'network_error',
  'timeout',
  'no_fill',
  'creative_failed',
  'ad_expired',
  'show_failed',
  'already_showing',
  'internal_error',
  'unsupported_platform',
  'module_unavailable',
  'invalid_argument',
]);

/** Every promise of this package rejects with an `QartveloAdsError`. */
export class QartveloAdsError extends Error implements QartveloAdsErrorInfo {
  readonly code: QartveloAdsErrorCode;
  /** Placement the failed call was made for, when there is one. */
  readonly placementId?: string;

  constructor(
    code: QartveloAdsErrorCode,
    message: string,
    placementId?: string
  ) {
    super(message);
    this.name = 'QartveloAdsError';
    this.code = code;
    if (placementId !== undefined) {
      this.placementId = placementId;
    }
    // Keeps `instanceof` working when the class is down-levelled by a bundler.
    Object.setPrototypeOf(this, QartveloAdsError.prototype);
  }
}

export function isQartveloAdsError(error: unknown): error is QartveloAdsError {
  return error instanceof QartveloAdsError;
}

/** Maps a wire code (any case) to a known code; unknown values become `internal_error`. */
export function toErrorCode(code: unknown): QartveloAdsErrorCode {
  if (typeof code !== 'string') {
    return 'internal_error';
  }
  const normalized = code.trim().toLowerCase();
  return ERROR_CODES.has(normalized)
    ? (normalized as QartveloAdsErrorCode)
    : 'internal_error';
}

/** Converts a native promise rejection (an Error carrying `code`) into an `QartveloAdsError`. */
export function toQartveloAdsError(
  error: unknown,
  placementId?: string
): QartveloAdsError {
  if (error instanceof QartveloAdsError) {
    return placementId === undefined || error.placementId !== undefined
      ? error
      : new QartveloAdsError(error.code, error.message, placementId);
  }
  const code =
    typeof error === 'object' && error !== null && 'code' in error
      ? (error as { code: unknown }).code
      : undefined;
  let message = 'Unknown QartveloAds error';
  if (error instanceof Error && error.message) {
    message = error.message;
  } else if (typeof error === 'string' && error) {
    message = error;
  }
  return new QartveloAdsError(toErrorCode(code), message, placementId);
}
