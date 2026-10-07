import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  jest,
} from '@jest/globals';
import { fake } from '../__fixtures__/fakeNative';
import { setPlatform } from '../__fixtures__/platform';
import { QartveloAds, QartveloAdsError, type QartveloAdsEvent } from '../index';

jest.mock(
  '../NativeQartveloAds',
  () => require('../__fixtures__/fakeNative').nativeModuleMock
);

const loaded = {
  type: 'loaded',
  placementId: 'game_end',
  format: 'interstitial',
  source: 'qartvelo',
  campaignId: 'cmp_12',
  creativeId: 'cr_34',
};

beforeEach(() => {
  fake.reset();
  setPlatform('android');
});

afterEach(() => {
  QartveloAds.removeAllListeners();
});

describe('event subscriptions', () => {
  it('opens one native subscription lazily and closes it with the last listener', () => {
    expect(fake.subscriberCount()).toBe(0);

    const a = QartveloAds.addListener('loaded', () => {});
    const b = QartveloAds.addListener('rewarded', () => {});
    expect(fake.native.onAdEvent).toHaveBeenCalledTimes(1);
    expect(fake.subscriberCount()).toBe(1);

    a.remove();
    expect(fake.subscriberCount()).toBe(1);
    b.remove();
    expect(fake.subscriberCount()).toBe(0);

    // A new listener re-opens the stream.
    const c = QartveloAds.addListener('clicked', () => {});
    expect(fake.native.onAdEvent).toHaveBeenCalledTimes(2);
    c.remove();
    expect(fake.subscriberCount()).toBe(0);
  });

  it('stops delivery after remove(), and remove() is idempotent', () => {
    const listener = jest.fn();
    const keep = jest.fn();
    const subscription = QartveloAds.addListener('loaded', listener);
    QartveloAds.addListener('loaded', keep);

    fake.emit(loaded);
    subscription.remove();
    subscription.remove();
    fake.emit(loaded);

    expect(listener).toHaveBeenCalledTimes(1);
    expect(keep).toHaveBeenCalledTimes(2);
  });

  it('treats each addListener call as its own subscription', () => {
    const listener = jest.fn();
    const first = QartveloAds.addListener('loaded', listener);
    QartveloAds.addListener('loaded', listener);

    fake.emit(loaded);
    first.remove();
    fake.emit(loaded);

    expect(listener).toHaveBeenCalledTimes(3);
  });

  it('removeAllListeners clears one type or everything', () => {
    const onLoaded = jest.fn();
    const onClicked = jest.fn();
    QartveloAds.addListener('loaded', onLoaded);
    QartveloAds.addListener('clicked', onClicked);

    QartveloAds.removeAllListeners('loaded');
    fake.emit(loaded);
    fake.emit({ ...loaded, type: 'clicked' });
    expect(onLoaded).not.toHaveBeenCalled();
    expect(onClicked).toHaveBeenCalledTimes(1);

    QartveloAds.removeAllListeners();
    expect(fake.subscriberCount()).toBe(0);
  });

  it('delivers typed payloads only to listeners of that type', () => {
    const onLoaded = jest.fn<(event: QartveloAdsEvent) => void>();
    const onDismissed = jest.fn();
    QartveloAds.addListener('loaded', onLoaded);
    QartveloAds.addListener('dismissed', onDismissed);

    fake.emit({ ...loaded, format: 'INTERSTITIAL', source: 'QARTVELO' });

    expect(onDismissed).not.toHaveBeenCalled();
    expect(onLoaded).toHaveBeenCalledWith({
      type: 'loaded',
      placementId: 'game_end',
      format: 'interstitial',
      source: 'qartvelo',
      campaignId: 'cmp_12',
      creativeId: 'cr_34',
    });
  });

  it('drops malformed native payloads instead of passing them on', () => {
    const listener = jest.fn();
    QartveloAds.addListener('loaded', listener);

    fake.emit({ ...loaded, placementId: '' });
    fake.emit({ ...loaded, source: 'facebook' });
    fake.emit({ ...loaded, format: undefined });
    fake.emit({ ...loaded, type: 'exploded' });

    expect(listener).not.toHaveBeenCalled();
  });

  it('keeps notifying other listeners when one throws or unsubscribes mid-dispatch', () => {
    const errors = jest.spyOn(console, 'error').mockImplementation(() => {});
    const calls: string[] = [];
    const self = QartveloAds.addListener('loaded', () => {
      calls.push('self-removing');
      self.remove();
    });
    QartveloAds.addListener('loaded', () => {
      calls.push('throwing');
      throw new Error('listener bug');
    });
    QartveloAds.addListener('loaded', () => {
      calls.push('healthy');
    });

    fake.emit(loaded);
    fake.emit(loaded);

    expect(calls).toEqual([
      'self-removing',
      'throwing',
      'healthy',
      'throwing',
      'healthy',
    ]);
    expect(errors).toHaveBeenCalledTimes(2);
    errors.mockRestore();
  });

  it('rejects unknown event names and non-function listeners', () => {
    expect(() => QartveloAds.addListener('reward' as never, () => {})).toThrow(
      QartveloAdsError
    );
    expect(() => QartveloAds.addListener('loaded', 'nope' as never)).toThrow(
      /listener must be a function/
    );
    expect(fake.subscriberCount()).toBe(0);
  });
});

describe('fallback event forwarding', () => {
  it('forwards the fallback sequence in order with normalized payloads', () => {
    const received: QartveloAdsEvent[] = [];
    const record = (event: QartveloAdsEvent) => received.push(event);
    QartveloAds.addListener('fallbackStarted', record);
    QartveloAds.addListener('loaded', record);
    QartveloAds.addListener('shown', record);

    fake.emit({
      type: 'fallbackStarted',
      placementId: 'game_end',
      format: 'interstitial',
      reason: 'no_fill',
    });
    fake.emit({
      type: 'loaded',
      placementId: 'game_end',
      format: 'interstitial',
      source: 'admob',
    });
    fake.emit({
      type: 'shown',
      placementId: 'game_end',
      format: 'interstitial',
      source: 'admob',
    });

    expect(received).toEqual([
      {
        type: 'fallbackStarted',
        placementId: 'game_end',
        format: 'interstitial',
        reason: 'no_fill',
      },
      {
        type: 'loaded',
        placementId: 'game_end',
        format: 'interstitial',
        source: 'admob',
      },
      {
        type: 'shown',
        placementId: 'game_end',
        format: 'interstitial',
        source: 'admob',
      },
    ]);
  });

  it('forwards every fallback reason and maps unknown ones to "error"', () => {
    const reasons: string[] = [];
    QartveloAds.addListener('fallbackStarted', (event) =>
      reasons.push(event.reason)
    );

    for (const reason of [
      'no_fill',
      'timeout',
      'error',
      'creative_failed',
      'disabled',
      'mystery',
    ]) {
      fake.emit({
        type: 'fallbackStarted',
        placementId: 'home_banner',
        format: 'banner',
        reason,
      });
    }

    expect(reasons).toEqual([
      'no_fill',
      'timeout',
      'error',
      'creative_failed',
      'disabled',
      'error',
    ]);
  });

  it('forwards no-ad and load failures with the error payload', () => {
    const noAd = jest.fn();
    const failed = jest.fn();
    QartveloAds.addListener('noAdAvailable', noAd);
    QartveloAds.addListener('loadFailed', failed);

    fake.emit({
      type: 'noAdAvailable',
      placementId: 'reward_coins',
      format: 'rewarded',
    });
    fake.emit({
      type: 'loadFailed',
      placementId: 'reward_coins',
      format: 'rewarded',
      error: { code: 'no_fill', message: 'No QartveloAds campaign available' },
    });
    fake.emit({
      type: 'loadFailed',
      placementId: 'unknown_code',
      error: { code: 'INVALID_PLACEMENT', message: '404' },
    });

    expect(noAd).toHaveBeenCalledWith({
      type: 'noAdAvailable',
      placementId: 'reward_coins',
      format: 'rewarded',
    });
    expect(failed).toHaveBeenNthCalledWith(1, {
      type: 'loadFailed',
      placementId: 'reward_coins',
      format: 'rewarded',
      error: { code: 'no_fill', message: 'No QartveloAds campaign available' },
    });
    expect(failed).toHaveBeenNthCalledWith(2, {
      type: 'loadFailed',
      placementId: 'unknown_code',
      error: { code: 'invalid_placement', message: '404' },
    });
  });
});
