// Qartvelo Ads MCP server: docs search, integration code generation, code lookup and test-mode
// API calls for AI coding agents.
import { McpServer, ResourceTemplate } from '@modelcontextprotocol/sdk/server/mcp.js';
import { z } from 'zod';
import { testAdRequest, getAppConfig, redact } from './api.js';
import { getDoc, loadDocs, searchDocs } from './docs.js';
import { CODES, explainCode } from './reference.js';
import { generateIntegration, renderMarkdown, SDK_VERSION } from './snippets.js';

const text = (value) => ({ content: [{ type: 'text', text: typeof value === 'string' ? value : JSON.stringify(value, null, 2) }] });
const failure = (message) => ({ content: [{ type: 'text', text: message }], isError: true });

const placementSchema = z.object({
	code: z.string().describe('Placement code from the dashboard, e.g. game_end'),
	format: z.enum(['banner', 'interstitial', 'rewarded']),
	admobAdUnitId: z.string().optional().describe('Optional AdMob ad unit id (contains "/") to set in code'),
});

export function createServer() {
	const server = new McpServer(
		{ name: 'qartvelo-ads', version: SDK_VERSION },
		{
			instructions: [
				'Tools for integrating the Qartvelo Ads SDK (Android Kotlin com.qartvelo.ads:core / React Native @qartvelo/react-native-ads).',
				'Search or read the docs before writing integration code; use generate_integration for a correct starting point.',
				'Ads are addressed by placement code. AdSource values are QARTVELO and ADMOB. The banner XML attribute is app:qartvelo_placementId.',
				'test_ad_request and get_app_config only send test-mode requests, which are never billed.',
			].join(' '),
		},
	);

	server.registerTool(
		'search_docs',
		{
			title: 'Search Qartvelo Ads docs',
			description: 'Keyword search over the Qartvelo Ads developer documentation. Returns the best matching sections with excerpts and page paths for read_doc.',
			inputSchema: {
				query: z.string().min(1).describe('Words or identifiers, e.g. "rewarded reward once" or "package_mismatch"'),
				limit: z.number().int().min(1).max(20).optional().describe('Maximum results (default 5)'),
			},
			annotations: { readOnlyHint: true, openWorldHint: false },
		},
		async ({ query, limit }) => {
			const results = searchDocs(query, limit ?? 5);
			return results.length ? text(results) : text(`No results for "${query}". Try list_docs.`);
		},
	);

	server.registerTool(
		'read_doc',
		{
			title: 'Read a docs page',
			description: 'Returns a full Qartvelo Ads docs page as Markdown, e.g. "android/banner", "react-native/usage", "api/ad-request".',
			inputSchema: { path: z.string().describe('Page path from list_docs or search_docs (a docs URL also works)') },
			annotations: { readOnlyHint: true, openWorldHint: false },
		},
		async ({ path }) => {
			const doc = getDoc(path);
			if (!doc) return failure(`No page "${path}". Call list_docs for the available paths.`);
			return text(`# ${doc.title}\n\n> ${doc.description}\n\nSource: ${doc.url}\n\n${doc.body}`);
		},
	);

	server.registerTool(
		'list_docs',
		{
			title: 'List docs pages',
			description: 'Lists every Qartvelo Ads docs page with its path, title and description.',
			inputSchema: {},
			annotations: { readOnlyHint: true, openWorldHint: false },
		},
		async () => text(loadDocs().map(({ path, title, description }) => ({ path, title, description }))),
	);

	server.registerTool(
		'generate_integration',
		{
			title: 'Generate integration code',
			description:
				'Generates Gradle, manifest, initialization and per-placement code for the Qartvelo Ads SDK, tailored to an app key and placements. Also flags common mistakes (App ID instead of ad unit ID, invalid codes).',
			inputSchema: {
				platform: z.enum(['android', 'react-native']),
				appKey: z.string().describe('App key from the publisher dashboard (app_...)'),
				placements: z.array(placementSchema).min(1),
				admobFallback: z.boolean().optional().describe('Include the AdMob fallback adapter (default true)'),
				admobAppId: z.string().optional().describe('The app\'s AdMob App ID (contains "~") for the manifest'),
			},
			annotations: { readOnlyHint: true, openWorldHint: false },
		},
		async (input) => text(renderMarkdown(generateIntegration(input))),
	);

	server.registerTool(
		'explain_code',
		{
			title: 'Explain an error or reason code',
			description:
				'Explains any Qartvelo Ads code: SDK errors (NO_FILL, ALREADY_SHOWING...), API errors (package_mismatch...), event rejections (duplicate, suspicious...), no-fill and fallback reasons.',
			inputSchema: { code: z.string().describe('The code, any case, e.g. "NOT_INITIALIZED" or "format_mismatch"') },
			annotations: { readOnlyHint: true, openWorldHint: false },
		},
		async ({ code }) => {
			const found = explainCode(code);
			return found ? text(found) : failure(`Unknown code "${code}". Known codes: ${Object.keys(CODES).join(', ')}`);
		},
	);

	server.registerTool(
		'get_app_config',
		{
			title: 'Get an app\'s remote config (test mode)',
			description:
				'Opens a TEST-MODE session for an app key and package name and returns the remote configuration: placement codes, formats, fallback units, timeouts and kill switches. Use it to verify credentials and discover placement codes. Never billed.',
			inputSchema: {
				appKey: z.string(),
				packageName: z.string().describe('The Android applicationId registered for the app'),
				baseUrl: z.string().url().optional().describe('API origin (default https://ads.qartvelo.com/)'),
			},
			annotations: { readOnlyHint: true, openWorldHint: true, idempotentHint: true },
		},
		async (input) => {
			try {
				const { init } = await getAppConfig(input);
				return text({ status: init.status, ms: init.ms, body: redact(init.body) });
			} catch (error) {
				return failure(`Request failed: ${error.message}`);
			}
		},
	);

	server.registerTool(
		'test_ad_request',
		{
			title: 'Send a test ad request',
			description:
				'Initializes a TEST-MODE session and requests one ad for a placement, returning both responses (tokens redacted) with timings. Verifies the app key, package name, placement code and format end to end. Test ads are never billed and no events are sent.',
			inputSchema: {
				appKey: z.string(),
				packageName: z.string(),
				placement: z.string().describe('Placement code'),
				format: z.enum(['banner', 'interstitial', 'rewarded']),
				forceNoFill: z.boolean().optional().describe('Force a no_fill to see the fallback answer'),
				baseUrl: z.string().url().optional(),
			},
			annotations: { readOnlyHint: true, openWorldHint: true, idempotentHint: true },
		},
		async (input) => {
			try {
				return text(await testAdRequest(input));
			} catch (error) {
				return failure(`Request failed: ${error.message}`);
			}
		},
	);

	server.registerResource(
		'docs-page',
		new ResourceTemplate('qartvelo-docs://{+path}', {
			list: async () => ({
				resources: loadDocs().map((d) => ({
					uri: `qartvelo-docs://${d.path}`,
					name: d.title,
					description: d.description,
					mimeType: 'text/markdown',
				})),
			}),
		}),
		{ title: 'Qartvelo Ads docs page', description: 'A page of the developer documentation', mimeType: 'text/markdown' },
		async (uri, { path }) => {
			const doc = getDoc(Array.isArray(path) ? path.join('/') : path);
			if (!doc) throw new Error(`No docs page ${uri.href}`);
			return { contents: [{ uri: uri.href, mimeType: 'text/markdown', text: `# ${doc.title}\n\n${doc.body}` }] };
		},
	);

	server.registerPrompt(
		'integrate-qartvelo-ads',
		{
			title: 'Integrate Qartvelo Ads',
			description: 'Step-by-step instructions for integrating the SDK into the current project.',
			argsSchema: {
				platform: z.enum(['android', 'react-native']).describe('android or react-native'),
				appKey: z.string().describe('App key (app_...)'),
				placements: z.string().describe('Comma-separated code:format list, e.g. home_banner:banner,game_end:interstitial'),
			},
		},
		({ platform, appKey, placements }) => ({
			messages: [
				{
					role: 'user',
					content: {
						type: 'text',
						text: [
							`Integrate the Qartvelo Ads SDK into this ${platform === 'android' ? 'Android (Kotlin)' : 'React Native'} project.`,
							`App key: ${appKey}. Placements: ${placements}.`,
							'1. Read the docs with read_doc ("get-started/quickstart" and the platform pages) before editing.',
							'2. Call generate_integration with these placements and apply the code to the right files.',
							'3. Use test mode in debug builds, keep the existing AdMob setup as fallback, and continue the app flow from every terminal show callback.',
							'4. If an app key and package name are known, verify them with test_ad_request.',
							'5. Finish with the release checklist from read_doc("android/release-checklist").',
						].join('\n'),
					},
				},
			],
		}),
	);

	return server;
}
