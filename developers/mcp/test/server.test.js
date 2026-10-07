import assert from 'node:assert/strict';
import { test } from 'node:test';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { InMemoryTransport } from '@modelcontextprotocol/sdk/inMemory.js';
import { createServer } from '../src/server.js';
import { getDoc, normalizePath, searchDocs } from '../src/docs.js';
import { explainCode } from '../src/reference.js';
import { generateIntegration, SDK_VERSION } from '../src/snippets.js';
import { redact } from '../src/api.js';

async function connect() {
	const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
	const server = createServer();
	const client = new Client({ name: 'test', version: '1.0.0' });
	await Promise.all([server.connect(serverTransport), client.connect(clientTransport)]);
	return client;
}

test('normalizes paths and URLs', () => {
	assert.equal(normalizePath('/android/banner/'), 'android/banner');
	assert.equal(normalizePath('https://developers.qartvelo.com/android/banner.md'), 'android/banner');
	assert.equal(normalizePath(''), 'index');
	assert.ok(getDoc('android/banner'));
});

test('search ranks the relevant page first', () => {
	assert.equal(searchDocs('package_mismatch applicationIdSuffix')[0].path, 'publishers/account-and-apps');
	assert.equal(searchDocs('qartvelo_placementId XML banner')[0].path, 'android/banner');
	assert.deepEqual(searchDocs('   '), []);
});

test('explains codes in any case', () => {
	assert.equal(explainCode('ALREADY_SHOWING').code, 'already_showing');
	assert.equal(explainCode('QartveloAdsErrorCode.NO_FILL').code, 'no_fill');
	assert.equal(explainCode('nope'), null);
});

test('generates Android code with the right attribute and units', () => {
	const result = generateIntegration({
		platform: 'android',
		appKey: 'app_abcdefghijklmnopqrstuvwx',
		placements: [
			{ code: 'home_banner', format: 'banner' },
			{ code: 'game_end', format: 'interstitial', admobAdUnitId: 'ca-app-pub-1234567890123456/1234567890' },
			{ code: 'reward_coins', format: 'rewarded' },
		],
	});
	const all = result.files.map((f) => f.code).join('\n');
	assert.deepEqual(result.problems, []);
	assert.match(all, /app:qartvelo_placementId="home_banner"/);
	assert.match(all, /"game_end" to "ca-app-pub-1234567890123456\/1234567890"/);
	assert.ok(all.includes(`com.qartvelo.ads:admob:${SDK_VERSION}`));
	assert.match(all, /fun showRewardedRewardCoins/);
});

test('flags an AdMob App ID used as an ad unit and bad codes', () => {
	const result = generateIntegration({
		platform: 'react-native',
		appKey: 'app_abcdefghijklmnopqrstuvwx',
		admobFallback: false,
		placements: [{ code: 'Game-End', format: 'interstitial', admobAdUnitId: 'ca-app-pub-1~2' }],
	});
	assert.equal(result.problems.length, 2);
	assert.ok(!result.files.some((f) => f.path === 'android/gradle.properties'));
});

test('redacts tokens', () => {
	assert.deepEqual(redact({ session_token: 'abcd', ad: { impression_token: 'xy', id: 'ad_1' } }), {
		session_token: '<4 chars>',
		ad: { impression_token: '<2 chars>', id: 'ad_1' },
	});
});

test('serves tools, resources and prompts over MCP', async () => {
	const client = await connect();
	const { tools } = await client.listTools();
	assert.deepEqual(tools.map((t) => t.name).sort(), [
		'explain_code', 'generate_integration', 'get_app_config', 'list_docs', 'read_doc', 'search_docs', 'test_ad_request',
	]);

	const read = await client.callTool({ name: 'read_doc', arguments: { path: 'android/rewarded' } });
	assert.match(read.content[0].text, /onReward/);

	const missing = await client.callTool({ name: 'read_doc', arguments: { path: 'nope' } });
	assert.equal(missing.isError, true);

	const { resources } = await client.listResources();
	assert.ok(resources.some((r) => r.uri === 'qartvelo-docs://android/banner'));
	const resource = await client.readResource({ uri: 'qartvelo-docs://api/ad-request' });
	assert.match(resource.contents[0].text, /no_eligible_campaign/);

	const prompt = await client.getPrompt({
		name: 'integrate-qartvelo-ads',
		arguments: { platform: 'android', appKey: 'app_x', placements: 'game_end:interstitial' },
	});
	assert.match(prompt.messages[0].content.text, /generate_integration/);
	await client.close();
});
