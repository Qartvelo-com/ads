import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import { StyleSheet, type NativeSyntheticEvent } from 'react-native';
import TestRenderer, { act, type ReactTestRenderer } from 'react-test-renderer';
import { bannerRecorder } from '../__fixtures__/fakeBannerView';
import { fake } from '../__fixtures__/fakeNative';
import { setPlatform } from '../__fixtures__/platform';
import { QartveloAdsBanner } from '../index';
import type {
  NativeBannerAdEvent,
  NativeBannerSizeEvent,
} from '../QartveloAdsBannerViewNativeComponent';

jest.mock(
  '../QartveloAdsBannerViewNativeComponent',
  () => require('../__fixtures__/fakeBannerView').bannerViewModuleMock
);
jest.mock(
  '../NativeQartveloAds',
  () => require('../__fixtures__/fakeNative').nativeModuleMock
);

function adEvent(nativeEvent: NativeBannerAdEvent) {
  return { nativeEvent } as NativeSyntheticEvent<NativeBannerAdEvent>;
}

function sizeEvent(nativeEvent: NativeBannerSizeEvent) {
  return { nativeEvent } as NativeSyntheticEvent<NativeBannerSizeEvent>;
}

function render(element: React.ReactElement): ReactTestRenderer {
  let renderer: ReactTestRenderer | undefined;
  act(() => {
    renderer = TestRenderer.create(element);
  });
  return renderer!;
}

beforeEach(() => {
  bannerRecorder.reset();
  fake.reset();
  setPlatform('android');
});

describe.each(['android', 'ios'] as const)('QartveloAdsBanner on %s', (os) => {
  beforeEach(() => setPlatform(os));
  it('passes inline sizing to the native banner, anchored by default', () => {
    render(
      <QartveloAdsBanner
        placementId="feed_banner"
        size="inline"
        maxHeight={200}
      />
    );
    expect(bannerRecorder.last().size).toBe('inline');
    expect(bannerRecorder.last().maxHeight).toBe(200);

    bannerRecorder.reset();
    render(<QartveloAdsBanner placementId="home_banner" />);
    expect(bannerRecorder.last().size).toBe('anchored');
    expect(bannerRecorder.last().maxHeight).toBe(250);
  });

  it('keeps native props stable when the parent re-renders with new inline props', () => {
    const renderer = render(
      <QartveloAdsBanner
        placementId="home_banner"
        style={{ width: '100%' }}
        onLoaded={() => {}}
      />
    );
    const first = bannerRecorder.last();

    for (let i = 0; i < 5; i++) {
      act(() => {
        renderer.update(
          <QartveloAdsBanner
            placementId="home_banner"
            style={{ width: '100%' }}
            onLoaded={() => {}}
            onClicked={() => {}}
          />
        );
      });
    }

    const placementIds = new Set(
      bannerRecorder.renders.map((p) => p.placementId)
    );
    expect(placementIds).toEqual(new Set(['home_banner']));
    // Handlers given to native never change identity, so no native prop updates are produced.
    for (const props of bannerRecorder.renders) {
      expect(props.onAdEvent).toBe(first.onAdEvent);
      expect(props.onSizeChange).toBe(first.onSizeChange);
    }
    // The native view is never re-created by re-renders; no imperative load is ever issued from JS.
    expect(bannerRecorder.mounts).toBe(1);
    expect(bannerRecorder.unmounts).toBe(0);
    expect(fake.native.loadInterstitial).not.toHaveBeenCalled();
    expect(fake.native.loadRewarded).not.toHaveBeenCalled();
  });

  it('passes a placementId change through once, keeping the same native view', () => {
    const renderer = render(<QartveloAdsBanner placementId="home_banner" />);
    act(() => {
      renderer.update(<QartveloAdsBanner placementId="game_banner" />);
    });
    act(() => {
      renderer.update(<QartveloAdsBanner placementId="game_banner" />);
    });

    const sequence = bannerRecorder.renders.map((p) => p.placementId);
    expect(sequence[0]).toBe('home_banner');
    expect(sequence.slice(1)).toEqual(expect.arrayContaining(['game_banner']));
    expect(sequence.filter((id) => id === 'home_banner')).toHaveLength(1);
    expect(bannerRecorder.mounts).toBe(1);
  });

  it('calls the latest callback for each native event, exactly once', () => {
    const stale = jest.fn();
    const current = jest.fn();
    const renderer = render(
      <QartveloAdsBanner placementId="home_banner" onLoaded={stale} />
    );
    act(() => {
      renderer.update(
        <QartveloAdsBanner placementId="home_banner" onLoaded={current} />
      );
    });

    act(() => {
      bannerRecorder.last().onAdEvent!(
        adEvent({
          type: 'loaded',
          placementId: 'home_banner',
          format: 'banner',
          source: 'qartvelo',
          campaignId: 'cmp_12',
          creativeId: 'cr_90',
        })
      );
    });

    expect(stale).not.toHaveBeenCalled();
    expect(current).toHaveBeenCalledTimes(1);
    expect(current).toHaveBeenCalledWith({
      type: 'loaded',
      placementId: 'home_banner',
      format: 'banner',
      source: 'qartvelo',
      campaignId: 'cmp_12',
      creativeId: 'cr_90',
    });
  });

  it('forwards fallback, impression, click and failure events to their callbacks', () => {
    const onFallbackStarted = jest.fn();
    const onLoaded = jest.fn();
    const onImpression = jest.fn();
    const onClicked = jest.fn();
    const onLoadFailed = jest.fn();
    const onNoAdAvailable = jest.fn();
    render(
      <QartveloAdsBanner
        placementId="home_banner"
        onFallbackStarted={onFallbackStarted}
        onLoaded={onLoaded}
        onImpression={onImpression}
        onClicked={onClicked}
        onLoadFailed={onLoadFailed}
        onNoAdAvailable={onNoAdAvailable}
      />
    );
    const emit = (event: NativeBannerAdEvent) =>
      act(() => {
        bannerRecorder.last().onAdEvent!(adEvent(event));
      });
    const base = { placementId: 'home_banner', format: 'banner' };

    emit({ ...base, type: 'fallbackStarted', reason: 'timeout' });
    emit({ ...base, type: 'loaded', source: 'admob' });
    emit({ ...base, type: 'impression', source: 'admob' });
    emit({ ...base, type: 'clicked', source: 'admob' });
    emit({ ...base, type: 'noAdAvailable' });
    emit({
      ...base,
      type: 'loadFailed',
      errorCode: 'no_fill',
      errorMessage: 'No QartveloAds campaign available',
    });

    expect(onFallbackStarted).toHaveBeenCalledWith({
      type: 'fallbackStarted',
      placementId: 'home_banner',
      format: 'banner',
      reason: 'timeout',
    });
    expect(onLoaded).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'loaded', source: 'admob' })
    );
    expect(onImpression).toHaveBeenCalledTimes(1);
    expect(onClicked).toHaveBeenCalledTimes(1);
    expect(onNoAdAvailable).toHaveBeenCalledTimes(1);
    expect(onLoadFailed).toHaveBeenCalledWith({
      type: 'loadFailed',
      placementId: 'home_banner',
      format: 'banner',
      error: { code: 'no_fill', message: 'No QartveloAds campaign available' },
    });
  });

  it('sizes itself from the rendered creative unless the app sets a height', () => {
    const onSizeChange = jest.fn();
    const renderer = render(
      <QartveloAdsBanner
        placementId="home_banner"
        style={{ width: '100%' }}
        onSizeChange={onSizeChange}
      />
    );
    expect(StyleSheet.flatten(bannerRecorder.last().style)).toMatchObject({
      width: '100%',
      height: 0,
    });

    act(() => {
      bannerRecorder.last().onSizeChange!(
        sizeEvent({ width: 320, height: 50 })
      );
    });
    expect(onSizeChange).toHaveBeenCalledWith({ width: 320, height: 50 });
    expect(StyleSheet.flatten(bannerRecorder.last().style)).toMatchObject({
      height: 50,
    });

    act(() => {
      renderer.update(
        <QartveloAdsBanner placementId="home_banner" style={{ height: 120 }} />
      );
    });
    expect(StyleSheet.flatten(bannerRecorder.last().style)).toMatchObject({
      height: 120,
    });
    expect(bannerRecorder.mounts).toBe(1);
  });

  it('unmounts the native view when the component unmounts', () => {
    const renderer = render(<QartveloAdsBanner placementId="home_banner" />);
    act(() => renderer.unmount());
    expect(bannerRecorder.unmounts).toBe(1);
  });
});

describe('QartveloAdsBanner on platforms without the SDK', () => {
  it('renders nothing and reports unsupported_platform once per placement', () => {
    setPlatform('web');
    const onLoadFailed = jest.fn();
    const renderer = render(
      <QartveloAdsBanner
        placementId="home_banner"
        onLoadFailed={onLoadFailed}
      />
    );
    act(() => {
      renderer.update(
        <QartveloAdsBanner
          placementId="home_banner"
          onLoadFailed={onLoadFailed}
        />
      );
    });

    expect(renderer.toJSON()).toBeNull();
    expect(bannerRecorder.renders).toHaveLength(0);
    expect(onLoadFailed).toHaveBeenCalledTimes(1);
    expect(onLoadFailed).toHaveBeenCalledWith(
      expect.objectContaining({
        type: 'loadFailed',
        placementId: 'home_banner',
        format: 'banner',
        error: expect.objectContaining({ code: 'unsupported_platform' }),
      })
    );
  });
});
