/**
 * QartveloAds React Native example: banner, interstitial and rewarded ads served by the QartveloAds Android
 * SDK, with test mode and forced no-fill to demonstrate the automatic AdMob fallback.
 */
import { useCallback, useEffect, useState, type ReactNode } from 'react';
import {
  Pressable,
  ScrollView,
  StatusBar,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  View,
} from 'react-native';
import {
  SafeAreaProvider,
  useSafeAreaInsets,
} from 'react-native-safe-area-context';
import {
  QartveloAds,
  QartveloAdsBanner,
  isQartveloAdsError,
  type QartveloAdsEvent,
  type QartveloAdsEventType,
} from '@qartvelo/react-native-ads';

/** Seeded demo app (backend `php artisan migrate:fresh --seed`). */
const APP_KEY = 'app_demo_rn_example_0001';
/** The host machine as seen from the Android emulator. */
const DEFAULT_BASE_URL = 'http://10.0.2.2:8000/';

const PLACEMENTS = {
  banner: 'home_banner',
  interstitial: 'game_end',
  rewarded: 'reward_coins',
} as const;

/**
 * Google's public test ad units for the seeded placements. They let the AdMob fallback work even
 * before the QartveloAds backend has delivered its placement configuration.
 */
const ADMOB_TEST_UNITS: Record<string, string> = {
  home_banner: 'ca-app-pub-3940256099942544/9214589741',
  game_end: 'ca-app-pub-3940256099942544/1033173712',
  reward_coins: 'ca-app-pub-3940256099942544/5224354917',
};

const EVENT_TYPES: QartveloAdsEventType[] = [
  'loaded',
  'loadFailed',
  'shown',
  'impression',
  'clicked',
  'dismissed',
  'rewarded',
  'fallbackStarted',
  'noAdAvailable',
];

const MAX_LOG_ENTRIES = 150;

type InitState = 'idle' | 'initializing' | 'ready' | 'degraded';

interface LogEntry {
  id: number;
  time: string;
  text: string;
  tone: 'normal' | 'fallback' | 'error' | 'reward';
}

let nextLogId = 1;

function describeError(error: unknown): string {
  if (isQartveloAdsError(error)) {
    return `${error.code}: ${error.message}`;
  }
  return error instanceof Error ? error.message : String(error);
}

function describeEvent(
  event: QartveloAdsEvent,
): Pick<LogEntry, 'text' | 'tone'> {
  const where = `${event.placementId} (${event.format ?? '?'})`;
  switch (event.type) {
    case 'fallbackStarted':
      return {
        text: `fallbackStarted ${where} reason=${event.reason}`,
        tone: 'fallback',
      };
    case 'loadFailed':
      return {
        text: `loadFailed ${where} ${event.error.code}: ${event.error.message}`,
        tone: 'error',
      };
    case 'noAdAvailable':
      return { text: `noAdAvailable ${where}`, tone: 'error' };
    case 'rewarded':
      return {
        text: `rewarded ${where} via ${event.source}: ${event.reward.amount} ${event.reward.type}`,
        tone: 'reward',
      };
    default: {
      const campaign = event.campaignId
        ? ` ${event.campaignId}/${event.creativeId}`
        : '';
      return {
        text: `${event.type} ${where} via ${event.source}${campaign}`,
        tone: event.source === 'admob' ? 'fallback' : 'normal',
      };
    }
  }
}

function useEventLog() {
  const [entries, setEntries] = useState<LogEntry[]>([]);

  const append = useCallback(
    (text: string, tone: LogEntry['tone'] = 'normal') => {
      const time = new Date().toLocaleTimeString();
      setEntries(previous =>
        [{ id: nextLogId++, time, text, tone }, ...previous].slice(
          0,
          MAX_LOG_ENTRIES,
        ),
      );
    },
    [],
  );

  useEffect(() => {
    // Global SDK events of every placement, banners included.
    const subscriptions = EVENT_TYPES.map(type =>
      QartveloAds.addListener(type, event => {
        const { text, tone } = describeEvent(event);
        append(text, tone);
      }),
    );
    return () => subscriptions.forEach(subscription => subscription.remove());
  }, [append]);

  return { entries, append, clear: () => setEntries([]) };
}

function App() {
  return (
    <SafeAreaProvider>
      <StatusBar barStyle="dark-content" />
      <DemoScreen />
    </SafeAreaProvider>
  );
}

function DemoScreen() {
  const insets = useSafeAreaInsets();
  const log = useEventLog();

  const [baseUrl, setBaseUrl] = useState(DEFAULT_BASE_URL);
  const [testMode, setTestMode] = useState(true);
  const [forceNoFill, setForceNoFill] = useState(false);
  const [initState, setInitState] = useState<InitState>('idle');
  const [initMessage, setInitMessage] = useState('Not initialized');

  const [bannerMounted, setBannerMounted] = useState(true);
  const [bannerStatus, setBannerStatus] = useState('Waiting for initialize()');
  const [renderCount, setRenderCount] = useState(0);

  const [interstitialStatus, setInterstitialStatus] = useState('Not loaded');
  const [rewardedStatus, setRewardedStatus] = useState('Not loaded');
  const [coins, setCoins] = useState(0);

  const initialized = initState === 'ready' || initState === 'degraded';

  const initialize = async () => {
    setInitState('initializing');
    setInitMessage('Initializing...');
    try {
      await QartveloAds.initialize({
        appKey: APP_KEY,
        baseUrl,
        testMode,
        testForceNoFill: forceNoFill,
        requestTimeoutMs: 800,
        logLevel: 'debug',
        admobAdUnits: ADMOB_TEST_UNITS,
      });
      setInitState('ready');
      setInitMessage('Initialized');
      log.append('initialize: ok');
    } catch (error) {
      // The SDK stays usable (cached config + AdMob fallback) even when initialization fails.
      setInitState('degraded');
      setInitMessage(`Fallback mode - ${describeError(error)}`);
      log.append(`initialize failed: ${describeError(error)}`, 'error');
    }
  };

  const loadInterstitial = async () => {
    setInterstitialStatus('Loading...');
    try {
      const info = await QartveloAds.loadInterstitial(PLACEMENTS.interstitial);
      setInterstitialStatus(`Ready from ${info.source}`);
    } catch (error) {
      setInterstitialStatus(`Load failed - ${describeError(error)}`);
    }
  };

  const showInterstitial = async () => {
    try {
      const result = await QartveloAds.showInterstitial(
        PLACEMENTS.interstitial,
      );
      setInterstitialStatus(
        result.shown
          ? `Shown from ${result.source} and dismissed`
          : 'No ad was ready',
      );
    } catch (error) {
      setInterstitialStatus(`Show failed - ${describeError(error)}`);
    }
  };

  const loadRewarded = async () => {
    setRewardedStatus('Loading...');
    try {
      const info = await QartveloAds.loadRewarded(PLACEMENTS.rewarded);
      setRewardedStatus(`Ready from ${info.source}`);
    } catch (error) {
      setRewardedStatus(`Load failed - ${describeError(error)}`);
    }
  };

  const showRewarded = async () => {
    try {
      const result = await QartveloAds.showRewarded(PLACEMENTS.rewarded);
      if (!result.shown) {
        setRewardedStatus('No ad was ready');
      } else if (result.rewarded && result.reward) {
        // Grant the reward from the show result only: it is delivered exactly once per show.
        const amount = result.reward.amount;
        setCoins(previous => previous + amount);
        setRewardedStatus(
          `Rewarded ${amount} ${result.reward.type} (via ${result.source})`,
        );
      } else {
        setRewardedStatus(`Closed early, no reward (via ${result.source})`);
      }
    } catch (error) {
      setRewardedStatus(`Show failed - ${describeError(error)}`);
    }
  };

  const checkReady = async () => {
    try {
      const [interstitial, rewarded] = await Promise.all([
        QartveloAds.isInterstitialReady(PLACEMENTS.interstitial),
        QartveloAds.isRewardedReady(PLACEMENTS.rewarded),
      ]);
      log.append(`isReady: interstitial=${interstitial} rewarded=${rewarded}`);
    } catch (error) {
      log.append(`isReady failed: ${describeError(error)}`, 'error');
    }
  };

  return (
    <ScrollView
      style={styles.screen}
      contentContainerStyle={[
        styles.content,
        { paddingTop: insets.top + 12, paddingBottom: insets.bottom + 24 },
      ]}
      keyboardShouldPersistTaps="handled"
    >
      <Text style={styles.title}>QartveloAds React Native example</Text>
      <Text style={styles.muted}>App key {APP_KEY}</Text>

      <Section title="1. Initialize">
        <Text style={styles.label}>Backend base URL</Text>
        <TextInput
          value={baseUrl}
          onChangeText={setBaseUrl}
          editable={!initialized}
          autoCapitalize="none"
          autoCorrect={false}
          keyboardType="url"
          style={[styles.input, initialized && styles.inputLocked]}
        />
        <Toggle
          label="Test mode (built-in test ads, nothing billed)"
          value={testMode}
          onValueChange={setTestMode}
          disabled={initialized}
        />
        <Toggle
          label="Force QartveloAds no-fill (shows the AdMob fallback)"
          value={forceNoFill}
          onValueChange={setForceNoFill}
          disabled={initialized || !testMode}
        />
        {!testMode && forceNoFill ? (
          <Text style={styles.muted}>
            Forced no-fill only applies in test mode.
          </Text>
        ) : null}
        <Button
          title={
            initState === 'initializing' ? 'Initializing...' : 'Initialize'
          }
          onPress={initialize}
          disabled={initState !== 'idle'}
        />
        <Text style={[styles.status, initState === 'degraded' && styles.error]}>
          {initMessage}
        </Text>
        {initialized ? (
          <Text style={styles.muted}>
            Options apply once per process. Restart the app to change them.
          </Text>
        ) : null}
      </Section>

      <Section title="2. Banner (home_banner)">
        {bannerMounted ? (
          <QartveloAdsBanner
            placementId={PLACEMENTS.banner}
            style={styles.banner}
            onLoaded={event => setBannerStatus(`Loaded from ${event.source}`)}
            onLoadFailed={event =>
              setBannerStatus(
                `Failed - ${event.error.code}: ${event.error.message}`,
              )
            }
            onFallbackStarted={event =>
              setBannerStatus(`Falling back to AdMob (${event.reason})`)
            }
            onClicked={() => setBannerStatus('Clicked')}
          />
        ) : (
          <Text style={styles.muted}>Banner unmounted</Text>
        )}
        <Text style={styles.status}>{bannerStatus}</Text>
        <View style={styles.row}>
          <Button
            title={bannerMounted ? 'Unmount banner' : 'Mount banner'}
            onPress={() => setBannerMounted(mounted => !mounted)}
            compact
          />
          <Button
            title={`Re-render screen (${renderCount})`}
            onPress={() => setRenderCount(count => count + 1)}
            compact
          />
        </View>
        <Text style={styles.muted}>
          Re-renders and remounts reuse the loaded banner: watch the log, no new
          "loaded" event appears until the placement's refresh interval.
        </Text>
      </Section>

      <Section title="3. Interstitial (game_end)">
        <View style={styles.row}>
          <Button
            title="Load"
            onPress={loadInterstitial}
            disabled={!initialized}
            compact
          />
          <Button
            title="Show"
            onPress={showInterstitial}
            disabled={!initialized}
            compact
          />
        </View>
        <Text style={styles.status}>{interstitialStatus}</Text>
      </Section>

      <Section title="4. Rewarded (reward_coins)">
        <View style={styles.row}>
          <Button
            title="Load"
            onPress={loadRewarded}
            disabled={!initialized}
            compact
          />
          <Button
            title="Show"
            onPress={showRewarded}
            disabled={!initialized}
            compact
          />
          <Button
            title="isReady?"
            onPress={checkReady}
            disabled={!initialized}
            compact
          />
        </View>
        <Text style={styles.status}>{rewardedStatus}</Text>
        <Text style={styles.coins}>Coins: {coins}</Text>
      </Section>

      <Section title="5. Event log">
        <View style={styles.row}>
          <Button title="Clear log" onPress={log.clear} compact />
        </View>
        {log.entries.length === 0 ? (
          <Text style={styles.muted}>No events yet.</Text>
        ) : (
          log.entries.map(entry => (
            <Text
              key={entry.id}
              style={[styles.logLine, toneStyles[entry.tone]]}
            >
              {entry.time} {entry.text}
            </Text>
          ))
        )}
      </Section>
    </ScrollView>
  );
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <View style={styles.section}>
      <Text style={styles.sectionTitle}>{title}</Text>
      {children}
    </View>
  );
}

function Toggle(props: {
  label: string;
  value: boolean;
  onValueChange: (value: boolean) => void;
  disabled?: boolean;
}) {
  return (
    <View style={styles.toggle}>
      <Text style={[styles.toggleLabel, props.disabled && styles.disabledText]}>
        {props.label}
      </Text>
      <Switch
        value={props.value}
        onValueChange={props.onValueChange}
        disabled={props.disabled}
      />
    </View>
  );
}

function Button(props: {
  title: string;
  onPress: () => void;
  disabled?: boolean;
  compact?: boolean;
}) {
  return (
    <Pressable
      accessibilityRole="button"
      onPress={props.onPress}
      disabled={props.disabled}
      style={({ pressed }) => [
        styles.button,
        props.compact && styles.buttonCompact,
        props.disabled && styles.buttonDisabled,
        pressed && styles.buttonPressed,
      ]}
    >
      <Text style={styles.buttonText}>{props.title}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: '#f3f4f6' },
  content: { paddingHorizontal: 16 },
  title: { fontSize: 22, fontWeight: '700', color: '#111827' },
  muted: { fontSize: 12, color: '#6b7280', marginTop: 4 },
  section: {
    marginTop: 16,
    padding: 14,
    borderRadius: 10,
    backgroundColor: '#ffffff',
  },
  sectionTitle: {
    fontSize: 16,
    fontWeight: '600',
    color: '#111827',
    marginBottom: 8,
  },
  label: { fontSize: 13, color: '#374151' },
  input: {
    marginTop: 4,
    marginBottom: 8,
    paddingHorizontal: 10,
    paddingVertical: 8,
    borderWidth: 1,
    borderColor: '#d1d5db',
    borderRadius: 6,
    color: '#111827',
  },
  inputLocked: { backgroundColor: '#f9fafb', color: '#6b7280' },
  toggle: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginVertical: 4,
  },
  toggleLabel: { flex: 1, fontSize: 13, color: '#374151', marginRight: 8 },
  disabledText: { color: '#9ca3af' },
  row: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginTop: 8 },
  button: {
    marginTop: 8,
    paddingVertical: 10,
    paddingHorizontal: 14,
    borderRadius: 6,
    backgroundColor: '#2563eb',
    alignItems: 'center',
  },
  buttonCompact: { marginTop: 0 },
  buttonDisabled: { backgroundColor: '#9ca3af' },
  buttonPressed: { opacity: 0.8 },
  buttonText: { color: '#ffffff', fontWeight: '600' },
  status: { marginTop: 8, fontSize: 13, color: '#111827' },
  error: { color: '#b91c1c' },
  banner: { width: '100%', backgroundColor: '#f9fafb' },
  coins: { marginTop: 6, fontSize: 15, fontWeight: '600', color: '#047857' },
  logLine: { fontSize: 11, fontFamily: 'monospace', marginTop: 3 },
});

const toneStyles = StyleSheet.create({
  normal: { color: '#111827' },
  fallback: { color: '#b45309' },
  error: { color: '#b91c1c' },
  reward: { color: '#047857' },
});

export default App;
