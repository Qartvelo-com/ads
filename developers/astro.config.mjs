// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import starlightLlmsTxt from 'starlight-llms-txt';

// Public developer documentation for Qartvelo Ads, served at https://developers.qartvelo.com.
// Override the origin with DOCS_SITE when previewing elsewhere.
const site = process.env.DOCS_SITE ?? 'https://developers.qartvelo.com';

export default defineConfig({
	site,
	trailingSlash: 'ignore',
	integrations: [
		starlight({
			title: 'Qartvelo Ads Developers',
			description:
				'Integrate Qartvelo Ads into Android and React Native apps: banner, interstitial and rewarded ads with automatic fallback to your own AdMob units.',
			logo: { src: './src/assets/logo.svg', replacesTitle: false },
			favicon: '/favicon.svg',
			social: [
				{ icon: 'github', label: 'GitHub', href: 'https://github.com/Qartvelo-com/ads' },
				{ icon: 'npm', label: 'npm', href: 'https://www.npmjs.com/package/@qartvelo/react-native-ads' },
			],
			editLink: { baseUrl: 'https://github.com/Qartvelo-com/ads/edit/main/developers/' },
			lastUpdated: true,
			customCss: ['./src/styles/custom.css'],
			components: {
				PageTitle: './src/components/PageTitle.astro',
			},
			head: [
				{ tag: 'link', attrs: { rel: 'alternate', type: 'text/plain', title: 'llms.txt', href: '/llms.txt' } },
			],
			sidebar: [
				{
					label: 'Get started',
					items: [
						{ slug: 'get-started/introduction' },
						{ slug: 'get-started/quickstart' },
						{ slug: 'get-started/how-it-works' },
						{ slug: 'get-started/test-mode' },
					],
				},
				{
					label: 'Publishers',
					items: [
						{ slug: 'publishers/account-and-apps' },
						{ slug: 'publishers/placements' },
						{ slug: 'publishers/reports-and-payouts' },
					],
				},
				{
					label: 'Android SDK',
					items: [
						{ slug: 'android/installation' },
						{ slug: 'android/initialization' },
						{ slug: 'android/banner' },
						{ slug: 'android/interstitial' },
						{ slug: 'android/rewarded' },
						{ slug: 'android/events' },
						{ slug: 'android/api-reference' },
						{ slug: 'android/release-checklist' },
					],
				},
				{
					label: 'React Native',
					items: [
						{ slug: 'react-native/installation' },
						{ slug: 'react-native/usage' },
						{ slug: 'react-native/events-and-errors' },
						{ slug: 'react-native/api-reference' },
						{ slug: 'react-native/troubleshooting' },
					],
				},
				{
					label: 'Guides',
					items: [
						{ slug: 'guides/admob-fallback' },
						{ slug: 'guides/privacy' },
						{ slug: 'guides/custom-fallback-adapter' },
					],
				},
				{
					label: 'REST API',
					items: [
						{ slug: 'api/overview' },
						{ slug: 'api/initialize' },
						{ slug: 'api/ad-request' },
						{ slug: 'api/events' },
						{ slug: 'api/errors-and-limits' },
						{ label: 'OpenAPI spec', link: '/openapi.yaml', attrs: { target: '_blank' } },
					],
				},
				{
					label: 'Advertisers',
					items: [
						{ slug: 'advertisers/campaigns' },
						{ slug: 'advertisers/creatives' },
						{ slug: 'advertisers/targeting' },
						{ slug: 'advertisers/billing' },
					],
				},
				{
					label: 'Build with AI',
					badge: { text: 'New', variant: 'tip' },
					items: [
						{ slug: 'ai/overview' },
						{ slug: 'ai/llms-txt' },
						{ slug: 'ai/mcp-server' },
						{ slug: 'ai/agent-skill' },
						{ slug: 'ai/prompts' },
					],
				},
				{
					label: 'Resources',
					items: [
						{ slug: 'resources/troubleshooting' },
						{ slug: 'resources/faq' },
						{ slug: 'resources/glossary' },
						{ slug: 'resources/changelog' },
					],
				},
			],
			plugins: [
				starlightLlmsTxt({
					projectName: 'Qartvelo Ads',
					description:
						'Qartvelo Ads is a direct-sold ad network for Georgian Android apps. Publishers integrate the Kotlin SDK (`com.qartvelo.ads:core`) or the React Native plugin (`@qartvelo/react-native-ads`); Qartvelo Ads campaigns are served first and the SDK falls back automatically to the publisher\'s own AdMob ad units.',
					details: [
						'Important notes for code generation:',
						'',
						'- Android only today. The Kotlin package is `com.qartvelo.sdk`; the entry point is the `QartveloAds` object. The React Native API rejects with `unsupported_platform` on iOS.',
						'- Current version: 0.3.4. Gradle coordinates `com.qartvelo.ads:core:0.3.4` and optional `com.qartvelo.ads:admob:0.3.4` from Maven Central (`mavenCentral()`, no extra repository).',
						'- Ads are addressed by placement **code** (for example `game_end`) created in the publisher dashboard, never by numeric id.',
						'- The app key (`app_` + 24 characters) is public and goes in the app. The SDK secret must never be embedded in an app.',
						'- Use `testMode = true` (Kotlin) / `testMode: __DEV__` (React Native) in debug builds; test ads are never billed.',
						'- Grant rewards only from `onReward` (Kotlin) or `result.rewarded` (React Native), exactly once.',
						'- `AdSource` values are `QARTVELO` and `ADMOB` (React Native: `qartvelo` and `admob`). The banner XML attribute is `app:qartvelo_placementId`.',
					].join('\n'),
					promote: ['index*', 'get-started/**', 'android/**', 'react-native/**'],
					demote: ['resources/changelog', 'advertisers/**'],
					customSets: [
						{ label: 'Android SDK', paths: ['android/**', 'guides/admob-fallback'], description: 'Complete Kotlin SDK documentation' },
						{ label: 'React Native', paths: ['react-native/**', 'guides/admob-fallback'], description: 'Complete React Native plugin documentation' },
						{ label: 'REST API', paths: ['api/**'], description: 'The HTTP API used by the SDKs, for custom integrations' },
					],
					optionalLinks: [
						{ label: 'OpenAPI specification', url: `${site}/openapi.yaml`, description: 'Machine-readable description of the SDK REST API' },
						{ label: 'Agent skill', url: `${site}/skills/qartvelo-ads/SKILL.md`, description: 'Integration instructions packaged for AI coding agents' },
					],
				}),
			],
		}),
	],
});
