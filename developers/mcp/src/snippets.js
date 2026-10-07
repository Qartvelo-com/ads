// Generates integration code tailored to an app key and its placements. The output mirrors the
// Quickstart and platform pages of the developer docs.

export const SDK_VERSION = '0.3.2';
const CODE_PATTERN = /^[a-z0-9_]{2,64}$/;
const FORMATS = ['banner', 'interstitial', 'rewarded'];

/**
 * @param {{ platform: 'android' | 'react-native', appKey: string, admobFallback?: boolean,
 *   admobAppId?: string, placements: { code: string, format: string, admobAdUnitId?: string }[] }} input
 */
export function generateIntegration(input) {
	const placements = input.placements ?? [];
	const problems = [];
	if (!/^app_[A-Za-z0-9_]{4,60}$/.test(input.appKey ?? '')) {
		problems.push('appKey should look like app_ followed by the characters shown in the publisher dashboard.');
	}
	for (const p of placements) {
		if (!CODE_PATTERN.test(p.code)) problems.push(`Placement code "${p.code}" must match [a-z0-9_]{2,64}.`);
		if (!FORMATS.includes(p.format)) problems.push(`Placement "${p.code}" has unknown format "${p.format}" (banner, interstitial or rewarded).`);
		if (p.admobAdUnitId?.includes('~')) problems.push(`"${p.admobAdUnitId}" is an AdMob App ID (contains "~"), not an ad unit ID (contains "/").`);
	}
	const admob = input.admobFallback !== false;
	const files = input.platform === 'react-native' ? reactNative(input, placements, admob) : android(input, placements, admob);
	return { problems, files, notes: notes(admob) };
}

function notes(admob) {
	return [
		'Use test mode in debug builds (never click your own live ads).',
		'Continue the app flow from every terminal show callback; grant rewards only on confirmed completion.',
		admob
			? 'AdMob fallback: add YOUR AdMob App ID to AndroidManifest.xml and set your ad unit per placement in the dashboard or admobAdUnits.'
			: 'AdMob fallback disabled: no-fills report "no ad".',
		'Docs: https://developers.qartvelo.com/llms-full.txt',
	];
}

const byFormat = (placements, format) => placements.filter((p) => p.format === format);
const units = (placements) => placements.filter((p) => p.admobAdUnitId);

function manifest(admobAppId) {
	return {
		path: 'app/src/main/AndroidManifest.xml (inside <application>)',
		language: 'xml',
		code: `<meta-data
    android:name="com.google.android.gms.ads.APPLICATION_ID"
    android:value="${admobAppId || 'ca-app-pub-XXXXXXXXXXXXXXXX~YYYYYYYYYY'}" />`,
	};
}

function android(input, placements, admob) {
	const files = [
		{
			path: 'settings.gradle.kts',
			language: 'kotlin',
			code: `dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") {
            content { includeGroup("com.qartvelo.ads") }
        }
    }
}`,
		},
		{
			path: 'app/build.gradle.kts',
			language: 'kotlin',
			code: `android {
    buildFeatures { buildConfig = true }
}

dependencies {
    implementation("com.qartvelo.ads:core:${SDK_VERSION}")${admob ? `\n    implementation("com.qartvelo.ads:admob:${SDK_VERSION}")` : ''}
}`,
		},
	];
	if (admob) files.push(manifest(input.admobAppId));

	const mapped = units(placements);
	const unitLines = mapped.length
		? `\n                admobAdUnits = mapOf(\n${mapped.map((p) => `                    "${p.code}" to "${p.admobAdUnitId}",`).join('\n')}\n                ),`
		: '';
	files.push({
		path: 'app/src/main/java/.../MyApp.kt (register with android:name=".MyApp")',
		language: 'kotlin',
		code: `import android.app.Application
import com.qartvelo.sdk.QartveloAds
import com.qartvelo.sdk.QartveloAdsLogLevel
import com.qartvelo.sdk.QartveloAdsOptions

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        QartveloAds.initialize(
            this,
            "${input.appKey}",
            QartveloAdsOptions(
                testMode = BuildConfig.DEBUG,${admob ? '' : '\n                admobFallback = false,'}
                logLevel = if (BuildConfig.DEBUG) QartveloAdsLogLevel.DEBUG else QartveloAdsLogLevel.ERROR,${unitLines}
            ),
        )
    }
}`,
	});

	for (const p of byFormat(placements, 'banner')) {
		files.push({
			path: `res/layout/... (banner "${p.code}")`,
			language: 'xml',
			code: `<com.qartvelo.sdk.QartveloAdsBannerView
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/banner_${p.code}"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    app:qartvelo_placementId="${p.code}" />

// Activity: findViewById<QartveloAdsBannerView>(R.id.banner_${p.code}).load()
// onDestroy(): banner.destroy()`,
		});
	}
	for (const p of byFormat(placements, 'interstitial')) {
		files.push({
			path: `Interstitial "${p.code}"`,
			language: 'kotlin',
			code: `import com.qartvelo.sdk.*

QartveloAds.loadInterstitial("${p.code}") // preload early

fun showInterstitial${pascal(p.code)}(activity: Activity, next: () -> Unit) {
    QartveloAds.showInterstitial(activity, "${p.code}", object : QartveloAdsListener {
        override fun onDismissed(info: QartveloAdsAdInfo) { next(); QartveloAds.loadInterstitial("${p.code}") }
        override fun onNoAdAvailable(placementId: String, format: AdFormat) = next()
        override fun onLoadFailed(placementId: String, error: QartveloAdsError) = next()
    })
}`,
		});
	}
	for (const p of byFormat(placements, 'rewarded')) {
		files.push({
			path: `Rewarded "${p.code}"`,
			language: 'kotlin',
			code: `import com.qartvelo.sdk.*

QartveloAds.loadRewarded("${p.code}") // preload; enable the button when QartveloAds.isRewardedReady("${p.code}")

fun showRewarded${pascal(p.code)}(activity: Activity, grant: () -> Unit, done: () -> Unit) {
    QartveloAds.showRewarded(activity, "${p.code}", object : QartveloAdsListener {
        override fun onReward(info: QartveloAdsAdInfo, reward: QartveloAdsReward) = grant() // once, after completion
        override fun onDismissed(info: QartveloAdsAdInfo) { done(); QartveloAds.loadRewarded("${p.code}") }
        override fun onNoAdAvailable(placementId: String, format: AdFormat) = done()
        override fun onLoadFailed(placementId: String, error: QartveloAdsError) = done()
    })
}`,
		});
	}
	return files;
}

function reactNative(input, placements, admob) {
	const files = [
		{ path: 'Terminal', language: 'sh', code: 'npm install @qartvelo/react-native-ads\nnpx react-native run-android  # rebuild the native app' },
		{
			path: 'android/build.gradle',
			language: 'groovy',
			code: `allprojects {
    repositories {
        maven {
            url "https://jitpack.io"
            content { includeGroup("com.qartvelo.ads") }
        }
    }
}`,
		},
	];
	if (admob) {
		files.push({ path: 'android/gradle.properties', language: 'properties', code: 'QartveloAds_admobEnabled=true' });
		files.push(manifest(input.admobAppId));
	}
	const mapped = units(placements);
	const unitLines = mapped.length
		? `\n      admobAdUnits: {\n${mapped.map((p) => `        ${p.code}: '${p.admobAdUnitId}',`).join('\n')}\n      },`
		: '';
	files.push({
		path: 'App.tsx',
		language: 'tsx',
		code: `import { useEffect } from 'react';
import { QartveloAds, isQartveloAdsError } from '@qartvelo/react-native-ads';

export function useQartveloAds() {
  useEffect(() => {
    QartveloAds.initialize({
      appKey: '${input.appKey}',
      testMode: __DEV__,${admob ? '' : '\n      admobFallback: false,'}
      logLevel: __DEV__ ? 'debug' : 'error',${unitLines}
    }).catch((error) => {
      // Not fatal: cached config and the fallback keep working.
      if (isQartveloAdsError(error)) console.warn(error.code, error.message);
    });
  }, []);
}`,
	});
	for (const p of byFormat(placements, 'banner')) {
		files.push({
			path: `Banner "${p.code}"`,
			language: 'tsx',
			code: `import { QartveloAdsBanner } from '@qartvelo/react-native-ads';

<QartveloAdsBanner placementId="${p.code}" style={{ width: '100%' }} />`,
		});
	}
	for (const p of byFormat(placements, 'interstitial')) {
		files.push({
			path: `Interstitial "${p.code}"`,
			language: 'ts',
			code: `QartveloAds.loadInterstitial('${p.code}').catch(() => {}); // preload early

export async function showInterstitial${pascal(p.code)}(): Promise<void> {
  try {
    await QartveloAds.showInterstitial('${p.code}'); // resolves on dismiss, or { shown: false }
  } catch {
    // already_showing, show_failed: just continue
  } finally {
    QartveloAds.loadInterstitial('${p.code}').catch(() => {});
  }
}`,
		});
	}
	for (const p of byFormat(placements, 'rewarded')) {
		files.push({
			path: `Rewarded "${p.code}"`,
			language: 'ts',
			code: `QartveloAds.loadRewarded('${p.code}').catch(() => {});

export async function showRewarded${pascal(p.code)}(): Promise<boolean> {
  try {
    const result = await QartveloAds.showRewarded('${p.code}');
    return result.rewarded; // true exactly once, only after completion
  } catch {
    return false;
  } finally {
    QartveloAds.loadRewarded('${p.code}').catch(() => {});
  }
}`,
		});
	}
	return files;
}

function pascal(code) {
	return code
		.split('_')
		.filter(Boolean)
		.map((s) => s[0].toUpperCase() + s.slice(1))
		.join('');
}

export function renderMarkdown(result) {
	const parts = [];
	if (result.problems.length) {
		parts.push('**Check these first:**\n' + result.problems.map((p) => `- ${p}`).join('\n'));
	}
	for (const f of result.files) {
		parts.push(`### ${f.path}\n\n\`\`\`${f.language}\n${f.code}\n\`\`\``);
	}
	parts.push('**Notes**\n' + result.notes.map((n) => `- ${n}`).join('\n'));
	return parts.join('\n\n');
}
