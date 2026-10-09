import fs from 'fs';
import path from 'path';
import { AndroidConfig, type InfoPlist } from 'expo/config-plugins';

export const PACKAGE = '@qartvelo/react-native-ads';
export const APPLICATION_ID = 'com.google.android.gms.ads.APPLICATION_ID';
export const DELAY_MEASUREMENT =
  'com.google.android.gms.ads.DELAY_APP_MEASUREMENT_INIT';
export const GRADLE_PROPERTY = 'QartveloAds_admobEnabled';
export const PODFILE_ENV = "ENV['QARTVELO_ADS_ADMOB_ENABLED'] = 'true'";

const APP_ID = /^ca-app-pub-\d{16}~\d{10}$/;

export interface AdMobOptions {
  androidAppId?: string;
  iosAppId?: string;
  delayAppMeasurementInit?: boolean;
  skAdNetworkItems?: string[];
}

export interface PluginOptions {
  admob?: AdMobOptions;
}

function fail(message: string): never {
  throw new Error(`${PACKAGE}: ${message}`);
}

/** Validates the plugin options. Without `admob`, only the core SDK is used. */
export function readOptions(options: unknown): PluginOptions {
  if (
    typeof options !== 'object' ||
    options === null ||
    Array.isArray(options)
  ) {
    fail(
      'plugin options must be an object like { "admob": { "androidAppId": "...", "iosAppId": "..." } }.'
    );
  }
  const admob = (options as { admob?: unknown }).admob;
  if (admob === undefined) {
    return {};
  }
  if (typeof admob !== 'object' || admob === null || Array.isArray(admob)) {
    fail('"admob" must be an object with androidAppId and iosAppId.');
  }
  const value = admob as Record<string, unknown>;
  for (const key of ['androidAppId', 'iosAppId'] as const) {
    if (value[key] !== undefined && typeof value[key] !== 'string') {
      fail(`admob.${key} must be a string.`);
    }
  }
  if (
    value.delayAppMeasurementInit !== undefined &&
    typeof value.delayAppMeasurementInit !== 'boolean'
  ) {
    fail('admob.delayAppMeasurementInit must be a boolean.');
  }
  const items = value.skAdNetworkItems;
  if (
    items !== undefined &&
    (!Array.isArray(items) || items.some((item) => typeof item !== 'string'))
  ) {
    fail('admob.skAdNetworkItems must be an array of SKAdNetwork identifiers.');
  }
  return { admob: value as AdMobOptions };
}

/** The AdMob App ID of the platform being built, validated. */
export function appIdFor(
  admob: AdMobOptions,
  platform: 'android' | 'ios'
): string {
  const key = platform === 'android' ? 'androidAppId' : 'iosAppId';
  const value = (admob[key] ?? '').trim();
  if (!APP_ID.test(value)) {
    fail(
      `admob.${key} must be an AdMob App ID like ca-app-pub-0000000000000000~0000000000 (found "${value}").`
    );
  }
  return value;
}

/** Google's recommended SKAdNetwork identifiers, shipped in plugin/skadnetwork.json. */
export function googleSkAdNetworkItems(): string[] {
  return JSON.parse(
    fs.readFileSync(path.join(__dirname, '..', 'skadnetwork.json'), 'utf8')
  ) as string[];
}

export function setAdMobGradleProperty(
  properties: AndroidConfig.Properties.PropertiesItem[]
): AndroidConfig.Properties.PropertiesItem[] {
  const rest = properties.filter(
    (item) => !(item.type === 'property' && item.key === GRADLE_PROPERTY)
  );
  return [...rest, { type: 'property', key: GRADLE_PROPERTY, value: 'true' }];
}

export function setAdMobManifest(
  manifest: AndroidConfig.Manifest.AndroidManifest,
  appId: string,
  delayAppMeasurementInit: boolean
): AndroidConfig.Manifest.AndroidManifest {
  const existing = AndroidConfig.Manifest.getMainApplicationMetaDataValue(
    manifest,
    APPLICATION_ID
  );
  if (existing && existing !== appId) {
    fail(
      `AndroidManifest.xml already sets ${APPLICATION_ID} to ${existing}, but admob.androidAppId is ${appId}. Keep one AdMob App ID.`
    );
  }
  const application =
    AndroidConfig.Manifest.getMainApplicationOrThrow(manifest);
  AndroidConfig.Manifest.addMetaDataItemToMainApplication(
    application,
    APPLICATION_ID,
    appId
  );
  if (delayAppMeasurementInit) {
    AndroidConfig.Manifest.addMetaDataItemToMainApplication(
      application,
      DELAY_MEASUREMENT,
      'true'
    );
  }
  return manifest;
}

/** Sets the Podfile ENV that RNQartveloAds.podspec reads, before use_native_modules! runs. */
export function addAdMobPodfileEnv(contents: string): string {
  if (contents.includes(PODFILE_ENV)) {
    return contents;
  }
  const line = `${PODFILE_ENV} # ${PACKAGE}: AdMob fallback`;
  const anchor = /^podfile_properties = .*$/m.exec(contents);
  if (!anchor) {
    return `${line}\n${contents}`;
  }
  const end = anchor.index + anchor[0].length;
  return `${contents.slice(0, end)}\n${line}${contents.slice(end)}`;
}

export function setAdMobInfoPlist(
  plist: InfoPlist,
  appId: string,
  delayAppMeasurementInit: boolean,
  extraSkAdNetworkItems: string[]
): InfoPlist {
  const existing = plist.GADApplicationIdentifier;
  if (typeof existing === 'string' && existing && existing !== appId) {
    fail(
      `Info.plist already sets GADApplicationIdentifier to ${existing}, but admob.iosAppId is ${appId}. Keep one AdMob App ID.`
    );
  }
  plist.GADApplicationIdentifier = appId;
  if (delayAppMeasurementInit) {
    plist.GADDelayAppMeasurementInit = true;
  }
  const items = (
    Array.isArray(plist.SKAdNetworkItems) ? [...plist.SKAdNetworkItems] : []
  ) as {
    SKAdNetworkIdentifier?: unknown;
  }[];
  const known = new Set(items.map((item) => item?.SKAdNetworkIdentifier));
  for (const raw of [...googleSkAdNetworkItems(), ...extraSkAdNetworkItems]) {
    const id = raw.trim();
    if (id && !known.has(id)) {
      known.add(id);
      items.push({ SKAdNetworkIdentifier: id });
    }
  }
  (plist as Record<string, unknown>).SKAdNetworkItems = items;
  return plist;
}
