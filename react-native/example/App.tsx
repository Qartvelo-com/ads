/**
 * QartveloAds React Native example: banner, interstitial and rewarded ads served by the QartveloAds native
 * SDK, with test mode and forced no-fill to demonstrate the automatic AdMob fallback.
 */
import { useCallback, useEffect, useState, type ReactNode } from 'react';
import {
  Platform,
  Pressable,
  ScrollView,
  StatusBar,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import {
  SafeAreaProvider,
  SafeAreaView,
  initialWindowMetrics,
} from 'react-native-safe-area-context';
import {
  QartveloAds,
  QartveloAdsBanner,
  isQartveloAdsError,
  type QartveloAdsEvent,
  type QartveloAdsEventType,
} from '@qartvelo/react-native-ads';

import { AD_OPTIONS, PLACEMENTS } from './src/AppConfig';

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

type InitState = 'initializing' | 'ready' | 'degraded';

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
  if (event.type === 'setupIssue') {
    return { text: `setupIssue ${event.code}: ${event.message}`, tone: 'error' };
  }
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
    <SafeAreaProvider initialMetrics={initialWindowMetrics}>
      <StatusBar barStyle="dark-content" />
      <DemoScreen />
    </SafeAreaProvider>
  );
}

function DemoScreen() {
  const log = useEventLog();
  const { append } = log;

  const [initState, setInitState] = useState<InitState>('initializing');
  const [initMessage, setInitMessage] = useState('Preparing test ads…');
  const [showEvents, setShowEvents] = useState(false);

  const [bannerMounted, setBannerMounted] = useState(true);
  const [bannerStatus, setBannerStatus] = useState('Waiting for initialize()');

  const [interstitialStatus, setInterstitialStatus] = useState('Not loaded');
  const [interstitialReady, setInterstitialReady] = useState(false);
  const [interstitialLoading, setInterstitialLoading] = useState(false);
  const [presenting, setPresenting] = useState(false);
  const [rewardedStatus, setRewardedStatus] = useState('Not loaded');
  const [rewardedReady, setRewardedReady] = useState(false);
  const [rewardedLoading, setRewardedLoading] = useState(false);
  const [coins, setCoins] = useState(0);

  const initialized = initState === 'ready' || initState === 'degraded';

  useEffect(() => {
    let active = true;
    QartveloAds.initialize(AD_OPTIONS).then(
      () => {
        if (!active) return;
        setInitState('ready');
        setInitMessage('Ready · Test ads');
        append('SDK ready');
      },
      error => {
        if (!active) return;
        // The native SDK can still use cached configuration and the AdMob fallback.
        setInitState('degraded');
        setInitMessage('Fallback mode');
        append(`Initialization: ${describeError(error)}`, 'error');
      },
    );
    return () => {
      active = false;
    };
  }, [append]);

  const loadInterstitial = async () => {
    setInterstitialLoading(true);
    setInterstitialReady(false);
    setInterstitialStatus('Loading…');
    try {
      const info = await QartveloAds.loadInterstitial(PLACEMENTS.interstitial);
      setInterstitialReady(true);
      setInterstitialStatus(`Ready from ${info.source}`);
    } catch (error) {
      setInterstitialStatus(`Load failed - ${describeError(error)}`);
    } finally {
      setInterstitialLoading(false);
    }
  };

  const showInterstitial = async () => {
    setPresenting(true);
    setInterstitialReady(false);
    setInterstitialStatus('Showing…');
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
    } finally {
      setPresenting(false);
    }
  };

  const loadRewarded = async () => {
    setRewardedLoading(true);
    setRewardedReady(false);
    setRewardedStatus('Loading…');
    try {
      const info = await QartveloAds.loadRewarded(PLACEMENTS.rewarded);
      setRewardedReady(true);
      setRewardedStatus(`Ready from ${info.source}`);
    } catch (error) {
      setRewardedStatus(`Load failed - ${describeError(error)}`);
    } finally {
      setRewardedLoading(false);
    }
  };

  const showRewarded = async () => {
    setPresenting(true);
    setRewardedReady(false);
    setRewardedStatus('Showing…');
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
    } finally {
      setPresenting(false);
    }
  };

  return (
    <SafeAreaView style={styles.screen} edges={['top', 'bottom']}>
      <ScrollView
        style={styles.screen}
        contentContainerStyle={[
          styles.content,
          { paddingTop: 12, paddingBottom: 24 },
        ]}
      >
        <Text style={styles.title}>QartveloAds React Native example</Text>
        <Text style={styles.muted}>
          Banner, interstitial and rewarded demos.
        </Text>
        <Text style={[styles.status, initState === 'degraded' && styles.error]}>
          {initMessage}
        </Text>

        <Section title="Banner">
          {initialized && bannerMounted ? (
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
            <Text style={styles.muted}>
              {initialized ? 'Banner hidden' : 'Preparing banner…'}
            </Text>
          )}
          <Text style={styles.status}>{bannerStatus}</Text>
          <View style={styles.row}>
            <Button
              title={bannerMounted ? 'Hide banner' : 'Show banner'}
              onPress={() => setBannerMounted(mounted => !mounted)}
              disabled={!initialized || presenting}
              compact
            />
          </View>
        </Section>

        <Section title="Inline banner (up to 250)">
          {initialized ? (
            <QartveloAdsBanner
              placementId={PLACEMENTS.inlineBanner}
              size="inline"
              maxHeight={250}
              style={styles.banner}
            />
          ) : (
            <Text style={styles.muted}>Preparing banner…</Text>
          )}
        </Section>

        <Section title="Interstitial">
          <View style={styles.row}>
            <Button
              title="Load"
              onPress={loadInterstitial}
              disabled={!initialized || interstitialLoading || presenting}
              compact
            />
            <Button
              title="Show"
              onPress={showInterstitial}
              disabled={!interstitialReady || presenting}
              compact
            />
          </View>
          <Text style={styles.status}>{interstitialStatus}</Text>
        </Section>

        <Section title="Rewarded">
          <View style={styles.row}>
            <Button
              title="Load"
              onPress={loadRewarded}
              disabled={!initialized || rewardedLoading || presenting}
              compact
            />
            <Button
              title="Show"
              onPress={showRewarded}
              disabled={!rewardedReady || presenting}
              compact
            />
          </View>
          <Text style={styles.status}>{rewardedStatus}</Text>
          <Text style={styles.coins}>Coins: {coins}</Text>
        </Section>

        <Button
          title={showEvents ? 'Hide events' : 'Show events'}
          onPress={() => setShowEvents(value => !value)}
        />
        {showEvents ? (
          <Section title="Events">
            <Button title="Clear events" onPress={log.clear} compact />
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
        ) : null}
      </ScrollView>
    </SafeAreaView>
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
  row: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginTop: 8 },
  button: {
    marginTop: 8,
    minHeight: 44,
    justifyContent: 'center',
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
  logLine: {
    fontSize: 11,
    fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
    marginTop: 3,
  },
});

const toneStyles = StyleSheet.create({
  normal: { color: '#111827' },
  fallback: { color: '#b45309' },
  error: { color: '#b91c1c' },
  reward: { color: '#047857' },
});

export default App;
