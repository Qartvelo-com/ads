/**
 * Stand-in for the codegen `QartveloAdsBannerView` host component. It records the props of every render
 * and the mount count, which is what reaches native code in the app.
 */
import { useEffect } from 'react';
import type { NativeProps } from '../QartveloAdsBannerViewNativeComponent';

export const bannerRecorder = {
  renders: [] as NativeProps[],
  mounts: 0,
  unmounts: 0,
  reset() {
    this.renders = [];
    this.mounts = 0;
    this.unmounts = 0;
  },
  last(): NativeProps {
    const props = this.renders[this.renders.length - 1];
    if (!props) {
      throw new Error('QartveloAdsBannerView was never rendered');
    }
    return props;
  },
};

function QartveloAdsBannerView(props: NativeProps) {
  bannerRecorder.renders.push(props);
  useEffect(() => {
    bannerRecorder.mounts += 1;
    return () => {
      bannerRecorder.unmounts += 1;
    };
  }, []);
  return null;
}

export const bannerViewModuleMock = {
  __esModule: true,
  default: QartveloAdsBannerView,
};
