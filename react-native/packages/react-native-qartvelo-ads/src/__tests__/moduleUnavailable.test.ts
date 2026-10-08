import { beforeEach, expect, it, jest } from '@jest/globals';
import { setPlatform } from '../__fixtures__/platform';
import { QartveloAds } from '../index';

// Android app built before the package was installed: the TurboModule does not exist.
jest.mock('../NativeQartveloAds', () => ({ __esModule: true, default: null }));

beforeEach(() => {
  setPlatform('android');
});

it('rejects with module_unavailable and keeps void APIs harmless', async () => {
  await expect(
    QartveloAds.initialize({ appKey: 'app_x' })
  ).rejects.toMatchObject({
    code: 'module_unavailable',
    message: expect.stringMatching(/Rebuild the native app/),
  });
  await expect(QartveloAds.showInterstitial('game_end')).rejects.toMatchObject({
    code: 'module_unavailable',
    placementId: 'game_end',
  });
  expect(QartveloAds.isSupported()).toBe(false);

  expect(() => QartveloAds.setPrivacy({ consentGiven: true })).not.toThrow();
  const subscription = QartveloAds.addListener('loaded', () => {});
  expect(() => subscription.remove()).not.toThrow();
});
