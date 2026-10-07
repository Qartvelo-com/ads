import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  jest,
} from '@jest/globals';
import { deferred, fake, nativeError } from '../__fixtures__/fakeNative';
import { setPlatform } from '../__fixtures__/platform';
import type { NativeShowResult } from '../NativeQartveloAds';
import {
  QartveloAds,
  QartveloAdsError,
  type RewardedShowResult,
} from '../index';

jest.mock(
  '../NativeQartveloAds',
  () => require('../__fixtures__/fakeNative').nativeModuleMock
);

/** Lets pending promise callbacks run. */
const flush = () => new Promise((resolve) => setImmediate(resolve));

beforeEach(() => {
  fake.reset();
  setPlatform('android');
});

afterEach(() => {
  QartveloAds.removeAllListeners();
});

describe('interstitial promises', () => {
  it('resolves load with the ad info of the source that will be shown', async () => {
    fake.native.loadInterstitial.mockResolvedValueOnce({
      placementId: 'game_end',
      format: 'interstitial',
      source: 'qartvelo',
      campaignId: 'cmp_12',
      creativeId: 'cr_34',
    });

    await expect(QartveloAds.loadInterstitial(' game_end ')).resolves.toEqual({
      placementId: 'game_end',
      format: 'interstitial',
      source: 'qartvelo',
      campaignId: 'cmp_12',
      creativeId: 'cr_34',
    });
    expect(fake.native.loadInterstitial).toHaveBeenCalledWith('game_end');
  });

  it('resolves show only when the native side settles (on dismiss)', async () => {
    const pending = deferred<NativeShowResult>();
    fake.native.showInterstitial.mockReturnValueOnce(pending.promise);

    let settled: unknown;
    const show = QartveloAds.showInterstitial('game_end').then((result) => {
      settled = result;
      return result;
    });
    await flush();
    expect(settled).toBeUndefined();

    pending.resolve({ shown: true, rewarded: false, source: 'admob' });
    await expect(show).resolves.toEqual({ shown: true, source: 'admob' });
  });

  it('resolves shown=false when no ad was available', async () => {
    fake.native.showInterstitial.mockResolvedValueOnce({
      shown: false,
      rewarded: false,
    });
    await expect(QartveloAds.showInterstitial('game_end')).resolves.toEqual({
      shown: false,
    });
  });

  it('rejects show errors with the native code and the placement', async () => {
    fake.native.showInterstitial.mockRejectedValueOnce(
      nativeError('already_showing', 'Another full-screen ad is showing')
    );
    const promise = QartveloAds.showInterstitial('game_end');
    await expect(promise).rejects.toBeInstanceOf(QartveloAdsError);
    await expect(promise).rejects.toMatchObject({
      code: 'already_showing',
      placementId: 'game_end',
    });
  });

  it('rejects load failures as QartveloAdsError', async () => {
    fake.native.loadInterstitial.mockRejectedValueOnce(
      nativeError('no_fill', 'No QartveloAds campaign available')
    );
    await expect(
      QartveloAds.loadInterstitial('game_end')
    ).rejects.toMatchObject({
      code: 'no_fill',
      message: 'No QartveloAds campaign available',
    });
  });

  it('rejects blank placement ids before reaching native code', async () => {
    await expect(QartveloAds.loadInterstitial('  ')).rejects.toMatchObject({
      code: 'invalid_argument',
    });
    await expect(
      QartveloAds.showInterstitial(undefined as never)
    ).rejects.toMatchObject({ code: 'invalid_argument' });
    expect(fake.native.loadInterstitial).not.toHaveBeenCalled();
    expect(fake.native.showInterstitial).not.toHaveBeenCalled();
  });

  it('keeps concurrent calls for one placement independent', async () => {
    const first = deferred<NativeShowResult>();
    const second = deferred<NativeShowResult>();
    fake.native.showInterstitial
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise);

    const a = QartveloAds.showInterstitial('game_end');
    const b = QartveloAds.showInterstitial('game_end');
    second.reject(nativeError('already_showing', 'busy'));
    first.resolve({ shown: true, rewarded: false, source: 'qartvelo' });

    await expect(a).resolves.toEqual({ shown: true, source: 'qartvelo' });
    await expect(b).rejects.toMatchObject({ code: 'already_showing' });
  });

  it('reports readiness from the SDK', async () => {
    fake.native.isInterstitialReady.mockResolvedValueOnce(true);
    await expect(QartveloAds.isInterstitialReady('game_end')).resolves.toBe(
      true
    );
    expect(fake.native.isInterstitialReady).toHaveBeenCalledWith('game_end');
  });
});

describe('rewarded', () => {
  it('loads through the rewarded native method', async () => {
    fake.native.loadRewarded.mockResolvedValueOnce({
      placementId: 'reward_coins',
      format: 'rewarded',
      source: 'admob',
    });
    await expect(QartveloAds.loadRewarded('reward_coins')).resolves.toEqual({
      placementId: 'reward_coins',
      format: 'rewarded',
      source: 'admob',
    });
    expect(fake.native.loadInterstitial).not.toHaveBeenCalled();
  });

  it('delivers one reward through the result and one rewarded event', async () => {
    const pending = deferred<NativeShowResult>();
    fake.native.showRewarded.mockReturnValueOnce(pending.promise);
    const onRewarded = jest.fn();
    QartveloAds.addListener('rewarded', onRewarded);

    const show = QartveloAds.showRewarded('reward_coins');
    // Native order: reward confirmed during the show, then the dismissal settles the promise.
    fake.emit({
      type: 'rewarded',
      placementId: 'reward_coins',
      format: 'rewarded',
      source: 'admob',
      reward: { type: 'coins', amount: 10 },
    });
    pending.resolve({
      shown: true,
      rewarded: true,
      source: 'admob',
      reward: { type: 'coins', amount: 10 },
    });

    const result: RewardedShowResult = await show;
    expect(result).toEqual({
      shown: true,
      rewarded: true,
      source: 'admob',
      reward: { type: 'coins', amount: 10 },
    });
    expect(onRewarded).toHaveBeenCalledTimes(1);
    expect(onRewarded).toHaveBeenCalledWith({
      type: 'rewarded',
      placementId: 'reward_coins',
      format: 'rewarded',
      source: 'admob',
      reward: { type: 'coins', amount: 10 },
    });

    // The promise is settled; nothing a late native event does can grant a second reward.
    fake.emit({
      type: 'dismissed',
      placementId: 'reward_coins',
      format: 'rewarded',
      source: 'admob',
    });
    await flush();
    expect(onRewarded).toHaveBeenCalledTimes(1);
  });

  it('reports rewarded=false when the user skipped', async () => {
    fake.native.showRewarded.mockResolvedValueOnce({
      shown: true,
      rewarded: false,
      source: 'qartvelo',
    });
    await expect(QartveloAds.showRewarded('reward_coins')).resolves.toEqual({
      shown: true,
      rewarded: false,
      source: 'qartvelo',
    });
  });

  it('never reports a reward without a shown ad or a reward payload', async () => {
    fake.native.showRewarded
      .mockResolvedValueOnce({
        shown: false,
        rewarded: true,
        reward: { type: 'reward', amount: 1 },
      })
      .mockResolvedValueOnce({
        shown: true,
        rewarded: true,
        source: 'qartvelo',
      });

    await expect(QartveloAds.showRewarded('reward_coins')).resolves.toEqual({
      shown: false,
      rewarded: false,
    });
    await expect(QartveloAds.showRewarded('reward_coins')).resolves.toEqual({
      shown: true,
      rewarded: false,
      source: 'qartvelo',
    });
  });

  it('maps the QartveloAds default reward', async () => {
    fake.native.showRewarded.mockResolvedValueOnce({
      shown: true,
      rewarded: true,
      source: 'qartvelo',
      reward: { type: '', amount: 1 },
    });
    await expect(
      QartveloAds.showRewarded('reward_coins')
    ).resolves.toMatchObject({
      rewarded: true,
      reward: { type: 'reward', amount: 1 },
    });
  });
});
