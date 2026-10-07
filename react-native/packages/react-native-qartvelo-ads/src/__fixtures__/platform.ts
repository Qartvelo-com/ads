import { Platform } from 'react-native';

/** The React Native Jest preset reports iOS; most tests run as Android. */
export function setPlatform(os: 'android' | 'ios'): void {
  (Platform as { OS: string }).OS = os;
}
