import { beforeEach, describe, expect, it, jest } from '@jest/globals';

jest.mock(
  '../NativeQartveloAds',
  () => require('../__fixtures__/fakeNative').nativeModuleMock
);

type FakeModule = typeof import('../__fixtures__/fakeNative');
type Api = typeof import('../index').QartveloAds;

const flush = () => new Promise((resolve) => setImmediate(resolve));

// The preloaded set is module state, so every test loads fresh copies of the package and the fake.
let fake: FakeModule['fake'];
let nativeError: FakeModule['nativeError'];
let QartveloAds: Api;

beforeEach(() => {
  jest.resetModules();
  ({ fake, nativeError } = require('../__fixtures__/fakeNative'));
  ({ QartveloAds } = require('../index'));
  require('../__fixtures__/platform').setPlatform('android');
  fake.reset();
  fake.native.loadInterstitial.mockImplementation(async (placementId) => ({
    placementId,
    format: 'interstitial',
    source: 'qartvelo',
  }));
  fake.native.loadRewarded.mockImplementation(async (placementId) => ({
    placementId,
    format: 'rewarded',
    source: 'qartvelo',
  }));
  fake.native.showInterstitial.mockResolvedValue({
    shown: true,
    rewarded: false,
    source: 'qartvelo',
  });
  fake.native.showRewarded.mockResolvedValue({
    shown: true,
    rewarded: true,
    source: 'qartvelo',
    reward: { type: 'reward', amount: 1 },
  });
});

describe('preload', () => {
  it('loads the listed placements once, trimmed', async () => {
    await QartveloAds.initialize({
      appKey: 'app_x',
      preload: {
        interstitial: [' game_end ', 'game_end'],
        rewarded: ['reward_coins'],
      },
    });
    await flush();
    expect(fake.native.loadInterstitial.mock.calls).toEqual([['game_end']]);
    expect(fake.native.loadRewarded.mock.calls).toEqual([['reward_coins']]);
  });

  it('reloads a preloaded placement after each show, and only that one', async () => {
    await QartveloAds.initialize({
      appKey: 'app_x',
      preload: { interstitial: ['game_end'] },
    });
    await flush();
    fake.native.loadInterstitial.mockClear();
    await QartveloAds.showInterstitial('game_end');
    await QartveloAds.showInterstitial('other_code');
    await flush();
    expect(fake.native.loadInterstitial.mock.calls).toEqual([['game_end']]);
  });

  it('reloads even when the show fails', async () => {
    await QartveloAds.initialize({
      appKey: 'app_x',
      preload: { rewarded: ['reward_coins'] },
    });
    await flush();
    fake.native.loadRewarded.mockClear();
    fake.native.showRewarded.mockRejectedValueOnce(
      nativeError('show_failed', 'Could not show')
    );
    await expect(
      QartveloAds.showRewarded('reward_coins')
    ).rejects.toMatchObject({ code: 'show_failed' });
    await flush();
    expect(fake.native.loadRewarded.mock.calls).toEqual([['reward_coins']]);
  });

  it('reloads a preloaded placement when loadIfNeeded gives up after a failed load', async () => {
    await QartveloAds.initialize({
      appKey: 'app_x',
      preload: { rewarded: ['reward_coins'] },
    });
    await flush();
    fake.native.loadRewarded.mockClear();
    fake.native.isRewardedReady.mockResolvedValue(false);
    fake.native.loadRewarded.mockRejectedValueOnce(
      nativeError('no_fill', 'No ad')
    );
    await expect(
      QartveloAds.showRewarded('reward_coins', { loadIfNeeded: true })
    ).resolves.toEqual({ shown: false, rewarded: false });
    expect(fake.native.showRewarded).not.toHaveBeenCalled();
    await flush();
    // The on-demand load that failed, then the reload that keeps the placement preloaded.
    expect(fake.native.loadRewarded.mock.calls).toEqual([
      ['reward_coins'],
      ['reward_coins'],
    ]);
  });

  it('rejects invalid preload lists without initializing', async () => {
    await expect(
      QartveloAds.initialize({
        appKey: 'app_x',
        preload: { interstitial: [''] },
      })
    ).rejects.toMatchObject({ code: 'invalid_argument' });
    expect(fake.native.initializeSdk).not.toHaveBeenCalled();
  });
});

describe('loadIfNeeded', () => {
  it('shows at once when an ad is ready', async () => {
    fake.native.isRewardedReady.mockResolvedValue(true);
    const result = await QartveloAds.showRewarded('reward_coins', {
      loadIfNeeded: true,
    });
    expect(result.rewarded).toBe(true);
    expect(fake.native.loadRewarded).not.toHaveBeenCalled();
  });

  it('loads first when nothing is ready', async () => {
    fake.native.isRewardedReady.mockResolvedValue(false);
    const result = await QartveloAds.showRewarded('reward_coins', {
      loadIfNeeded: true,
    });
    expect(fake.native.loadRewarded.mock.calls).toEqual([['reward_coins']]);
    expect(fake.native.showRewarded).toHaveBeenCalledTimes(1);
    expect(result).toMatchObject({ shown: true, rewarded: true });
  });

  it('resolves shown false when the load fails', async () => {
    fake.native.isInterstitialReady.mockResolvedValue(false);
    fake.native.loadInterstitial.mockRejectedValueOnce(
      nativeError('no_fill', 'No ad')
    );
    await expect(
      QartveloAds.showInterstitial('game_end', { loadIfNeeded: true })
    ).resolves.toEqual({ shown: false });
    fake.native.isRewardedReady.mockResolvedValue(false);
    fake.native.loadRewarded.mockRejectedValueOnce(
      nativeError('no_fill', 'No ad')
    );
    await expect(
      QartveloAds.showRewarded('reward_coins', { loadIfNeeded: true })
    ).resolves.toEqual({ shown: false, rewarded: false });
    expect(fake.native.showInterstitial).not.toHaveBeenCalled();
    expect(fake.native.showRewarded).not.toHaveBeenCalled();
  });

  it('never loads without the option', async () => {
    await QartveloAds.showRewarded('reward_coins');
    expect(fake.native.isRewardedReady).not.toHaveBeenCalled();
    expect(fake.native.loadRewarded).not.toHaveBeenCalled();
  });

  it('rejects invalid show options', async () => {
    await expect(
      QartveloAds.showRewarded('reward_coins', { loadIfNeeded: 'yes' } as never)
    ).rejects.toMatchObject({ code: 'invalid_argument' });
  });
});
