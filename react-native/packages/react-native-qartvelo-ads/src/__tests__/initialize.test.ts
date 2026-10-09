import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  jest,
} from '@jest/globals';
import { fake, nativeError } from '../__fixtures__/fakeNative';
import { setPlatform } from '../__fixtures__/platform';
import { QartveloAds, QartveloAdsError } from '../index';

jest.mock(
  '../NativeQartveloAds',
  () => require('../__fixtures__/fakeNative').nativeModuleMock
);

beforeEach(() => {
  fake.reset();
  setPlatform('android');
});

afterEach(() => {
  QartveloAds.removeAllListeners();
});

describe.each(['android', 'ios'] as const)(
  'QartveloAds.initialize on %s',
  (os) => {
    beforeEach(() => setPlatform(os));
    it('passes only the options that were set so native defaults apply', async () => {
      await QartveloAds.initialize({ appKey: '  app_demo_rn_example_0001 ' });

      expect(fake.native.initializeSdk).toHaveBeenCalledTimes(1);
      expect(fake.native.initializeSdk).toHaveBeenCalledWith({
        appKey: 'app_demo_rn_example_0001',
      });
    });

    it('maps every option to the native wire format', async () => {
      await QartveloAds.initialize({
        appKey: 'app_x',
        requestTimeoutMs: 800.4,
        testMode: true,
        testForceNoFill: true,
        testModeInDebugBuilds: false,
        admobFallback: false,
        logLevel: 'debug',
        baseUrl: ' http://10.0.2.2:8000/ ',
        admobAdUnits: {
          ' game_end ': ' ca-app-pub-3940256099942544/1033173712 ',
        },
      });

      expect(fake.native.initializeSdk).toHaveBeenCalledWith({
        appKey: 'app_x',
        requestTimeoutMs: 800,
        testMode: true,
        testForceNoFill: true,
        testModeInDebugBuilds: false,
        admobFallback: false,
        logLevel: 'debug',
        baseUrl: 'http://10.0.2.2:8000/',
        admobAdUnits: { game_end: 'ca-app-pub-3940256099942544/1033173712' },
      });
    });

    it('uses the value for the running platform', async () => {
      await QartveloAds.initialize({
        appKey: { android: 'app_android', ios: 'app_ios' },
        admobAdUnits: {
          home_banner: { android: 'ca-app-pub-1/111', ios: 'ca-app-pub-1/222' },
          game_end: 'ca-app-pub-1/333',
          other_only:
            os === 'android'
              ? { ios: 'ca-app-pub-1/444' }
              : { android: 'ca-app-pub-1/444' },
        },
        admobTestUnitsInDebugBuilds: false,
      });

      expect(fake.native.initializeSdk).toHaveBeenCalledWith({
        appKey: os === 'android' ? 'app_android' : 'app_ios',
        admobAdUnits: {
          home_banner:
            os === 'android' ? 'ca-app-pub-1/111' : 'ca-app-pub-1/222',
          game_end: 'ca-app-pub-1/333',
        },
        admobTestUnitsInDebugBuilds: false,
      });
    });

    it('rejects a per-platform appKey without a value for this platform', async () => {
      const promise = QartveloAds.initialize({
        appKey:
          os === 'android' ? { ios: 'app_ios' } : { android: 'app_android' },
      });
      await expect(promise).rejects.toMatchObject({
        code: 'invalid_argument',
        message: `appKey has no value for ${os}`,
      });
      expect(fake.native.initializeSdk).not.toHaveBeenCalled();
    });

    it.each([
      [{ appKey: '' }, /appKey/],
      [{ appKey: 'app_x', requestTimeoutMs: 0 }, /requestTimeoutMs/],
      [{ appKey: 'app_x', requestTimeoutMs: Number.NaN }, /requestTimeoutMs/],
      [{ appKey: 'app_x', logLevel: 'verbose' }, /logLevel/],
      [{ appKey: 'app_x', baseUrl: 'ftp://example.com' }, /baseUrl/],
      [{ appKey: 'app_x', testMode: 'yes' }, /testMode/],
      [{ appKey: 'app_x', admobAdUnits: { game_end: 42 } }, /admobAdUnits/],
      [{ appKey: 42 }, /appKey/],
      [{ appKey: { android: 7 } }, /appKey/],
      [{ appKey: { [os]: 7 } }, /appKey/],
      [
        { appKey: 'app_x', admobAdUnits: { game_end: { [os]: 7 } } },
        /admobAdUnits/,
      ],
      [
        { appKey: 'app_x', admobTestUnitsInDebugBuilds: 'no' },
        /admobTestUnitsInDebugBuilds/,
      ],
    ])(
      'rejects invalid options %p without calling native',
      async (options, message) => {
        const promise = QartveloAds.initialize(options as never);
        await expect(promise).rejects.toBeInstanceOf(QartveloAdsError);
        await expect(promise).rejects.toMatchObject({
          code: 'invalid_argument',
          message: expect.stringMatching(message),
        });
        expect(fake.native.initializeSdk).not.toHaveBeenCalled();
      }
    );

    it('maps a native rejection to an QartveloAdsError with a lower-case code', async () => {
      fake.native.initializeSdk.mockRejectedValueOnce(
        nativeError('network_error', 'QartveloAds backend unreachable')
      );
      await expect(
        QartveloAds.initialize({ appKey: 'app_x' })
      ).rejects.toMatchObject({
        name: 'QartveloAdsError',
        code: 'network_error',
        message: 'QartveloAds backend unreachable',
      });

      fake.native.initializeSdk.mockRejectedValueOnce(
        nativeError('NOT_INITIALIZED', '401: rejected')
      );
      await expect(
        QartveloAds.initialize({ appKey: 'app_x' })
      ).rejects.toMatchObject({
        code: 'not_initialized',
      });

      fake.native.initializeSdk.mockRejectedValueOnce(
        nativeError('E_SOMETHING', 'boom')
      );
      await expect(
        QartveloAds.initialize({ appKey: 'app_x' })
      ).rejects.toMatchObject({
        code: 'internal_error',
      });
    });

    it('forwards log level and privacy signals, keeping unknown signals unset', () => {
      QartveloAds.setLogLevel('info');
      QartveloAds.setPrivacy({ consentGiven: false, childDirected: undefined });

      expect(fake.native.setLogLevel).toHaveBeenCalledWith('info');
      expect(fake.native.setPrivacy).toHaveBeenCalledWith({
        consentGiven: false,
      });
      expect(() => QartveloAds.setLogLevel('loud' as never)).toThrow(
        QartveloAdsError
      );
    });

    it('reports the native initialization state', async () => {
      fake.native.isInitialized.mockResolvedValueOnce(false);
      await expect(QartveloAds.isInitialized()).resolves.toBe(false);
      expect(QartveloAds.isSupported()).toBe(true);
    });
  }
);

describe('platforms without the SDK', () => {
  it('rejects every promise API with unsupported_platform on web', async () => {
    setPlatform('web');

    await expect(
      QartveloAds.initialize({ appKey: 'app_x' })
    ).rejects.toMatchObject({
      code: 'unsupported_platform',
    });
    await expect(
      QartveloAds.loadInterstitial('game_end')
    ).rejects.toMatchObject({
      code: 'unsupported_platform',
      placementId: 'game_end',
    });
    await expect(
      QartveloAds.showRewarded('reward_coins')
    ).rejects.toMatchObject({
      code: 'unsupported_platform',
    });
    await expect(
      QartveloAds.isRewardedReady('reward_coins')
    ).rejects.toMatchObject({
      code: 'unsupported_platform',
    });
    expect(QartveloAds.isSupported()).toBe(false);

    // Void APIs and subscriptions stay harmless so shared code needs no platform checks.
    QartveloAds.setLogLevel('debug');
    const subscription = QartveloAds.addListener('loaded', () => {});
    subscription.remove();
    expect(fake.native.setLogLevel).not.toHaveBeenCalled();
    expect(fake.native.onAdEvent).not.toHaveBeenCalled();
    expect(fake.native.initializeSdk).not.toHaveBeenCalled();
  });
});
