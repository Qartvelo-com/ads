// Every code a developer or agent may run into, with the meaning and the fix. Kept in sync with
// the docs pages api/errors-and-limits, api/events, android/events and react-native/events-and-errors.

/** @type {Record<string, { kind: string, meaning: string, fix: string }>} */
export const CODES = {
	// SDK error codes (Kotlin UPPER_CASE, React Native lower_case)
	not_initialized: { kind: 'SDK error', meaning: 'initialize() was not called, the app key is empty, or the backend rejected the app key / package name.', fix: 'Call QartveloAds.initialize first. Check the app key, that applicationId equals the registered package name, and that the app is approved (or use test mode).' },
	invalid_placement: { kind: 'SDK error', meaning: 'Empty or unknown placement code, or a placement used with the wrong format.', fix: 'Use the exact placement code from the dashboard and the load method matching its format (banner view, loadInterstitial, loadRewarded).' },
	network_error: { kind: 'SDK error', meaning: 'The backend could not be reached and no fallback was available.', fix: 'Check connectivity and baseUrl. Add the AdMob adapter so loads fall back.' },
	timeout: { kind: 'SDK error / fallback reason', meaning: 'The backend did not answer within the request timeout (default 800 ms).', fix: 'Expected on slow networks; AdMob covers it. Raise the placement request timeout in the dashboard if it happens often.' },
	no_fill: { kind: 'SDK error / fallback reason', meaning: 'Neither Qartvelo Ads nor the fallback had an ad.', fix: 'Normal. Continue the app flow; hide the reward button. Configure an AdMob fallback unit to raise fill.' },
	creative_failed: { kind: 'SDK error / fallback reason', meaning: 'The creative could not be downloaded, decoded or rendered.', fix: 'Usually a slow or broken network; the fallback is used. Persistent failures: report the creative id to Qartvelo Ads.' },
	ad_expired: { kind: 'SDK error', meaning: 'The ad passed its 30-minute expiry before it was shown.', fix: 'Load closer to the moment you show; the SDK reloads automatically on the next load.' },
	show_failed: { kind: 'SDK error', meaning: 'The ad could not be displayed, for example no foreground Activity.', fix: 'Show from a resumed Activity; continue your flow.' },
	already_showing: { kind: 'SDK error', meaning: 'Another full-screen ad is on screen.', fix: 'Do not show two full-screen ads at once; wait for onDismissed.' },
	internal_error: { kind: 'SDK error', meaning: 'Unexpected failure inside the SDK (it never throws into app code).', fix: 'Enable DEBUG logs (adb logcat -s QartveloAds) and report the log.' },
	unsupported_platform: { kind: 'React Native error', meaning: 'Called on a platform without the SDK (iOS, web).', fix: 'Guard with QartveloAds.isSupported(); the React Native plugin supports Android only today (native iOS apps use the Swift SDK).' },
	module_unavailable: { kind: 'React Native error', meaning: 'The native module is not linked into the app binary.', fix: 'Rebuild the Android app: npx react-native run-android. A JS reload is not enough.' },
	invalid_argument: { kind: 'React Native error', meaning: 'A JavaScript argument was rejected before reaching native code.', fix: 'Pass a non-empty placement code string and valid options.' },
	// Fallback reasons
	error: { kind: 'fallback reason', meaning: 'Network or server error while requesting Qartvelo Ads.', fix: 'The fallback is used automatically.' },
	disabled: { kind: 'fallback reason', meaning: 'Qartvelo Ads is switched off for this placement, app or publisher (kill switch, paused or not approved).', fix: 'Check the placement status and the app/account approval in the dashboard. The SDK re-checks at most every 5 minutes.' },
	// REST API error codes
	invalid_app_key: { kind: 'API error 401', meaning: 'Unknown app key.', fix: 'Copy the app key (app_ + 24 characters) from the app page in the publisher dashboard.' },
	invalid_session: { kind: 'API error 401', meaning: 'Session token malformed, forged or issued for another app.', fix: 'Call /sdk/initialize again and use the new token with the same app key.' },
	session_expired: { kind: 'API error 401', meaning: 'Session token past its expiry (1 hour).', fix: 'Call /sdk/initialize again.' },
	package_mismatch: { kind: 'API error 403', meaning: 'package_name differs from the app registered for this key.', fix: 'The applicationId must equal the registered package exactly. Watch out for applicationIdSuffix such as .debug.' },
	platform_mismatch: { kind: 'API error 403', meaning: 'The app key belongs to the app registered for the other platform (Android vs iOS).', fix: 'Register the Android and iOS versions as two apps and use each one\'s own app key.' },
	app_not_approved: { kind: 'API error 403', meaning: 'The app is not approved yet and test_mode is false.', fix: 'Use test mode until an admin approves the app.' },
	placement_not_found: { kind: 'API error 404', meaning: 'No placement with that code in this app.', fix: 'Create the placement in the dashboard or fix the code (lowercase, [a-z0-9_]{2,64}).' },
	format_mismatch: { kind: 'API error 422', meaning: 'Requested format differs from the placement format.', fix: 'Use the load method that matches the placement format.' },
	validation_failed: { kind: 'API error 422', meaning: 'Missing or invalid fields.', fix: 'See error.fields in the response.' },
	rate_limited: { kind: 'API error 429', meaning: 'A rate limit was exceeded.', fix: 'Wait for the Retry-After header. Do not loop on loads.' },
	server_error: { kind: 'API error 500', meaning: 'Unexpected server error.', fix: 'Retry with backoff; the SDK falls back meanwhile.' },
	// Event rejection reasons
	duplicate: { kind: 'event rejection 409', meaning: 'The token was already used for this event type.', fix: 'Final. Never resend an event.' },
	invalid_token: { kind: 'event rejection 422', meaning: 'Bad signature or payload, wrong token type, or a reward on a non-rewarded placement.', fix: 'Pass the impression_token verbatim; send rewards only for rewarded placements.' },
	expired_token: { kind: 'event rejection 422', meaning: 'Impression after the ad expired, or click/reward more than one hour after that.', fix: 'Show ads before expires_at; send events promptly.' },
	request_mismatch: { kind: 'event rejection 422', meaning: 'The token belongs to another request_id.', fix: 'Send the request_id that came with the token.' },
	no_impression: { kind: 'event rejection 422', meaning: 'Click or reward without an accepted impression.', fix: 'Send the impression first, and only when the ad is on screen.' },
	suspicious: { kind: 'event rejection 422', meaning: 'A fraud rule rejected the event (click too fast, too many clicks or impressions, high CTR).', fix: 'Never click your own live ads; use test mode. See api/errors-and-limits.' },
	// No-fill reasons
	no_eligible_campaign: { kind: 'no-fill reason', meaning: 'No campaign matched the request or had budget left.', fix: 'Normal; the fallback is used.' },
	serving_disabled: { kind: 'no-fill reason', meaning: 'Global, publisher or app kill switch, or account/app not approved outside test mode.', fix: 'Check approvals in the dashboard or contact Qartvelo Ads.' },
	placement_disabled: { kind: 'no-fill reason', meaning: 'Placement paused, switched off, or disabled by an admin.', fix: 'Check the placement status in the dashboard.' },
	frequency_capped: { kind: 'no-fill reason', meaning: 'The placement frequency cap for this session is reached.', fix: 'Expected; adjust the cap on the placement if needed.' },
	test_no_fill: { kind: 'no-fill reason', meaning: 'test_force_no_fill / testForceNoFill was set.', fix: 'Turn it off to get test ads again.' },
};

export function explainCode(code) {
	const key = String(code).trim().toLowerCase().replace(/^.*\./, '');
	return CODES[key] ? { code: key, ...CODES[key] } : null;
}
