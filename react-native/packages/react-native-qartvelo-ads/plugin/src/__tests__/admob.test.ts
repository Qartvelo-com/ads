/**
 * @jest-environment node
 */
import { describe, expect, it } from '@jest/globals';
import { AndroidConfig, type InfoPlist } from 'expo/config-plugins';
import {
  APPLICATION_ID,
  DELAY_MEASUREMENT,
  GRADLE_PROPERTY,
  PODFILE_ENV,
  addAdMobPodfileEnv,
  appIdFor,
  googleSkAdNetworkItems,
  readOptions,
  setAdMobGradleProperty,
  setAdMobInfoPlist,
  setAdMobManifest,
} from '../admob';

const ANDROID_ID = 'ca-app-pub-3940256099942544~3347511713';
const IOS_ID = 'ca-app-pub-3940256099942544~1458002511';

function manifest(
  existing: Record<string, string> = {}
): AndroidConfig.Manifest.AndroidManifest {
  return {
    manifest: {
      $: { 'xmlns:android': 'http://schemas.android.com/apk/res/android' },
      application: [
        {
          '$': { 'android:name': '.MainApplication' },
          'meta-data': Object.entries(existing).map(([name, value]) => ({
            $: { 'android:name': name, 'android:value': value },
          })),
        },
      ],
    },
  } as AndroidConfig.Manifest.AndroidManifest;
}

function metaData(
  result: AndroidConfig.Manifest.AndroidManifest
): Record<string, string> {
  const items = result.manifest.application?.[0]?.['meta-data'] ?? [];
  return Object.fromEntries(
    items.map((item) => [item.$['android:name'], item.$['android:value'] ?? ''])
  );
}

describe('readOptions and appIdFor', () => {
  it('accepts no admob object (core SDK only)', () => {
    expect(readOptions({})).toEqual({});
  });

  it.each([
    [null, /plugin options/],
    [{ admob: 'x' }, /"admob" must be an object/],
    [{ admob: { androidAppId: 1 } }, /admob.androidAppId must be a string/],
    [{ admob: { delayAppMeasurementInit: 'yes' } }, /delayAppMeasurementInit/],
    [{ admob: { skAdNetworkItems: [1] } }, /skAdNetworkItems/],
  ])('rejects %p', (options, message) => {
    expect(() => readOptions(options)).toThrow(message);
  });

  it('validates the App ID of the platform being built and names the key', () => {
    expect(appIdFor({ androidAppId: ` ${ANDROID_ID} ` }, 'android')).toBe(
      ANDROID_ID
    );
    expect(() => appIdFor({ androidAppId: ANDROID_ID }, 'ios')).toThrow(
      /admob\.iosAppId/
    );
    expect(() => appIdFor({ iosAppId: 'ca-app-pub-123/456' }, 'ios')).toThrow(
      /admob\.iosAppId/
    );
  });
});

describe('Android', () => {
  it('adds the App ID, and the measurement delay only when asked', () => {
    expect(metaData(setAdMobManifest(manifest(), ANDROID_ID, false))).toEqual({
      [APPLICATION_ID]: ANDROID_ID,
    });
    expect(metaData(setAdMobManifest(manifest(), ANDROID_ID, true))).toEqual({
      [APPLICATION_ID]: ANDROID_ID,
      [DELAY_MEASUREMENT]: 'true',
    });
  });

  it('is idempotent and accepts the same existing App ID', () => {
    const once = setAdMobManifest(
      manifest({ [APPLICATION_ID]: ANDROID_ID }),
      ANDROID_ID,
      false
    );
    const twice = setAdMobManifest(once, ANDROID_ID, false);
    expect(twice.manifest.application?.[0]?.['meta-data']).toHaveLength(1);
  });

  it('refuses a different existing App ID', () => {
    expect(() =>
      setAdMobManifest(
        manifest({
          [APPLICATION_ID]: 'ca-app-pub-1111111111111111~2222222222',
        }),
        ANDROID_ID,
        false
      )
    ).toThrow(/already sets/);
  });

  it('turns the adapter on in gradle.properties exactly once', () => {
    const props: AndroidConfig.Properties.PropertiesItem[] = [
      { type: 'property', key: GRADLE_PROPERTY, value: 'false' },
      { type: 'property', key: 'newArchEnabled', value: 'true' },
    ];
    const result = setAdMobGradleProperty(setAdMobGradleProperty(props));
    expect(
      result.filter(
        (item) => item.type === 'property' && item.key === GRADLE_PROPERTY
      )
    ).toEqual([{ type: 'property', key: GRADLE_PROPERTY, value: 'true' }]);
    expect(result).toContainEqual({
      type: 'property',
      key: 'newArchEnabled',
      value: 'true',
    });
  });
});

describe('iOS', () => {
  const podfile = [
    "require 'json'",
    "podfile_properties = JSON.parse(File.read(File.join(__dir__, 'Podfile.properties.json'))) rescue {}",
    '',
    "target 'App' do",
    '  config = use_native_modules!(config_command)',
    'end',
  ].join('\n');

  it('enables the adapter in the Podfile before use_native_modules!, once', () => {
    const result = addAdMobPodfileEnv(addAdMobPodfileEnv(podfile));
    expect(result.split(PODFILE_ENV)).toHaveLength(2);
    expect(result.indexOf(PODFILE_ENV)).toBeGreaterThan(
      result.indexOf('podfile_properties = ')
    );
    expect(result.indexOf(PODFILE_ENV)).toBeLessThan(
      result.indexOf('use_native_modules!')
    );
  });

  it('prepends the line when the Podfile has no podfile_properties line', () => {
    expect(
      addAdMobPodfileEnv("target 'App' do\nend").startsWith(PODFILE_ENV)
    ).toBe(true);
  });

  it('writes the App ID, the delay and the SKAdNetwork list without duplicates', () => {
    const plist: InfoPlist = {
      SKAdNetworkItems: [
        { SKAdNetworkIdentifier: 'cstr6suwn9.skadnetwork' },
        { SKAdNetworkIdentifier: 'mine.skadnetwork' },
      ],
    };
    const result = setAdMobInfoPlist(plist, IOS_ID, true, [
      'example123.skadnetwork',
      'mine.skadnetwork',
    ]);
    const ids = (
      result.SKAdNetworkItems as { SKAdNetworkIdentifier: string }[]
    ).map((item) => item.SKAdNetworkIdentifier);
    expect(result.GADApplicationIdentifier).toBe(IOS_ID);
    expect(result.GADDelayAppMeasurementInit).toBe(true);
    expect(new Set(ids).size).toBe(ids.length);
    expect(ids).toEqual(
      expect.arrayContaining([
        ...googleSkAdNetworkItems(),
        'mine.skadnetwork',
        'example123.skadnetwork',
      ])
    );
    expect(ids).toHaveLength(googleSkAdNetworkItems().length + 2);
  });

  it('accepts the same existing App ID and refuses a different one', () => {
    expect(
      setAdMobInfoPlist({ GADApplicationIdentifier: IOS_ID }, IOS_ID, false, [])
        .GADApplicationIdentifier
    ).toBe(IOS_ID);
    expect(() =>
      setAdMobInfoPlist(
        { GADApplicationIdentifier: 'ca-app-pub-1111111111111111~2222222222' },
        IOS_ID,
        false,
        []
      )
    ).toThrow(/already sets/);
  });
});
