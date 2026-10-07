// Test-mode calls against the Qartvelo Ads REST API. Every request sets test_mode, so nothing is
// billed and the app does not need to be approved yet. Events are never sent.

export const DEFAULT_BASE_URL = 'https://ads.qartvelo.com/';

function endpoint(baseUrl, path) {
	const base = (baseUrl || process.env.QARTVELO_ADS_BASE_URL || DEFAULT_BASE_URL).replace(/\/+$/, '');
	return `${base}/api/v1/${path}`;
}

async function post(baseUrl, path, body, timeoutMs = 10000) {
	const started = Date.now();
	const res = await fetch(endpoint(baseUrl, path), {
		method: 'POST',
		headers: { 'Content-Type': 'application/json', Accept: 'application/json', 'User-Agent': 'qartvelo-ads-mcp' },
		body: JSON.stringify(body),
		signal: AbortSignal.timeout(timeoutMs),
	});
	const text = await res.text();
	let json;
	try {
		json = JSON.parse(text);
	} catch {
		json = { raw: text.slice(0, 500) };
	}
	return { status: res.status, ms: Date.now() - started, body: json };
}

/** Redacts tokens from a response so they never end up in an agent transcript. */
function redact(value) {
	if (Array.isArray(value)) return value.map(redact);
	if (value && typeof value === 'object') {
		return Object.fromEntries(
			Object.entries(value).map(([k, v]) => [k, /token/.test(k) && typeof v === 'string' ? `<${v.length} chars>` : redact(v)]),
		);
	}
	return value;
}

/** Initializes a test session and returns the app's remote configuration (placements and kill switches). */
export async function getAppConfig({ appKey, packageName, baseUrl }) {
	const init = await post(baseUrl, 'sdk/initialize', {
		app_key: appKey,
		package_name: packageName,
		platform: 'android',
		sdk_version: 'mcp',
		test_mode: true,
	});
	return { init, sessionToken: init.status === 200 ? init.body.session_token : null };
}

/** Initializes a test session and requests one test ad for a placement. */
export async function testAdRequest({ appKey, packageName, placement, format, forceNoFill = false, baseUrl }) {
	const { init, sessionToken } = await getAppConfig({ appKey, packageName, baseUrl });
	const result = { initialize: { status: init.status, ms: init.ms, body: redact(init.body) } };
	if (!sessionToken) return result;
	const ad = await post(baseUrl, 'ads/request', {
		app_key: appKey,
		placement,
		format,
		session_token: sessionToken,
		language: 'ka',
		android_version: '14',
		screen_width: 1080,
		screen_height: 2400,
		test_mode: true,
		test_force_no_fill: forceNoFill,
	});
	result.adRequest = { status: ad.status, ms: ad.ms, body: redact(ad.body) };
	return result;
}

export { redact };
