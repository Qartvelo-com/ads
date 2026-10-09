import { useEffect } from 'react';
import { Button, SafeAreaView, StyleSheet, Text } from 'react-native';
import { QartveloAds, QartveloAdsBanner } from '@qartvelo/react-native-ads';

// Replace with the app keys and placement codes of your apps in the Qartvelo Ads dashboard.
const APP_KEY = { android: 'app_android_key_from_dashboard', ios: 'app_ios_key_from_dashboard' };

export default function App() {
  useEffect(() => {
    QartveloAds.initialize({
      appKey: APP_KEY,
      testMode: true,
      preload: { interstitial: ['game_end'], rewarded: ['reward_coins'] },
    }).catch(() => {
      // Not fatal: the SDK keeps working on cached config and the AdMob fallback.
    });
  }, []);

  return (
    <SafeAreaView style={styles.root}>
      <Text style={styles.title}>Qartvelo Ads Expo example</Text>
      <Button
        title="Show a reward video"
        onPress={() => {
          QartveloAds.showRewarded('reward_coins', { loadIfNeeded: true }).catch(() => {});
        }}
      />
      <QartveloAdsBanner placementId="home_banner" style={styles.banner} />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, justifyContent: 'space-between' },
  title: { margin: 16, fontSize: 18 },
  banner: { width: '100%' },
});
