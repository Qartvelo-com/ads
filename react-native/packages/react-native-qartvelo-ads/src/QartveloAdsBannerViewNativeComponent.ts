/**
 * Codegen spec of the native banner view (Fabric). Private: apps render `<QartveloAdsBanner />`.
 *
 * The native view owns loading, refresh and request de-duplication through the SDK's per-placement
 * banner controller. Only a change of `placementId`, `size` or `maxHeight` makes it load again.
 */
import type { CodegenTypes, HostComponent, ViewProps } from 'react-native';
import { codegenNativeComponent } from 'react-native';

export type NativeBannerAdEvent = Readonly<{
  type: string;
  placementId: string;
  format: string;
  source?: string;
  campaignId?: string;
  creativeId?: string;
  errorCode?: string;
  errorMessage?: string;
  reason?: string;
}>;

/** Natural size of the rendered creative in dp; 0 x 0 while nothing is rendered. */
export type NativeBannerSizeEvent = Readonly<{
  width: CodegenTypes.Double;
  height: CodegenTypes.Double;
}>;

export interface NativeProps extends ViewProps {
  placementId?: string;
  size?: CodegenTypes.WithDefault<'anchored' | 'inline', 'anchored'>;
  maxHeight?: CodegenTypes.WithDefault<CodegenTypes.Double, 250>;
  onAdEvent?: CodegenTypes.DirectEventHandler<NativeBannerAdEvent>;
  onSizeChange?: CodegenTypes.DirectEventHandler<NativeBannerSizeEvent>;
}

export default codegenNativeComponent<NativeProps>(
  'QartveloAdsBannerView'
) as HostComponent<NativeProps>;
