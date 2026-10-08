import { Platform } from 'react-native';
import type { QartveloAdsInitOptions } from '@qartvelo/react-native-ads';

/** Replace these demo values with your app key and placement codes when integrating. */
export const PLACEMENTS = {
  banner: 'home_banner',
  interstitial: 'game_end',
  rewarded: 'reward_coins',
} as const;

/** Sample settings are kept in code. Restart the app after changing SDK options. */
export const AD_OPTIONS: QartveloAdsInitOptions = {
  appKey:
    Platform.OS === 'ios'
      ? 'app_dYWiPE5Pp2vvc0fuHkYi3ggo'
      : 'app_demo_rn_example_0001',
  // Android's seeded demo app uses the local backend. iOS uses the SDK's HTTPS default.
  ...(Platform.OS === 'android' ? { baseUrl: 'http://10.0.2.2:8000/' } : {}),
  testMode: true,
  // Set true and restart to try the optional AdMob adapter with Google's test ads.
  testForceNoFill: false,
  requestTimeoutMs: 800,
  logLevel: 'debug',
  admobAdUnits:
    Platform.OS === 'ios'
      ? {
          home_banner: 'ca-app-pub-3940256099942544/2435281174',
          game_end: 'ca-app-pub-3940256099942544/4411468910',
          reward_coins: 'ca-app-pub-3940256099942544/1712485313',
        }
      : {
          home_banner: 'ca-app-pub-3940256099942544/9214589741',
          game_end: 'ca-app-pub-3940256099942544/1033173712',
          reward_coins: 'ca-app-pub-3940256099942544/5224354917',
        },
};
