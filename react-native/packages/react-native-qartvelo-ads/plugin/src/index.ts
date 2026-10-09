import fs from 'fs';
import path from 'path';
import {
  type ConfigPlugin,
  createRunOncePlugin,
  withAndroidManifest,
  withGradleProperties,
  withInfoPlist,
  withPodfile,
} from 'expo/config-plugins';
import {
  type PluginOptions,
  addAdMobPodfileEnv,
  appIdFor,
  readOptions,
  setAdMobGradleProperty,
  setAdMobInfoPlist,
  setAdMobManifest,
} from './admob';

// plugin/src and plugin/build both sit two levels below the package root.
const pkg = JSON.parse(
  fs.readFileSync(path.join(__dirname, '..', '..', 'package.json'), 'utf8')
) as {
  name: string;
  version: string;
};

/**
 * Expo config plugin of @qartvelo/react-native-ads. With { admob: { ... } } it turns on the native
 * AdMob fallback adapter and writes the AdMob App IDs, which Google Mobile Ads needs at start-up.
 * Each App ID is validated only for the platform being prebuilt.
 */
const withQartveloAds: ConfigPlugin<PluginOptions | void> = (
  config,
  rawOptions
) => {
  const { admob } = readOptions(rawOptions ?? {});
  if (!admob) {
    return config;
  }
  const delay = admob.delayAppMeasurementInit === true;

  config = withGradleProperties(config, (cfg) => {
    cfg.modResults = setAdMobGradleProperty(cfg.modResults);
    return cfg;
  });
  config = withAndroidManifest(config, (cfg) => {
    cfg.modResults = setAdMobManifest(
      cfg.modResults,
      appIdFor(admob, 'android'),
      delay
    );
    return cfg;
  });
  config = withPodfile(config, (cfg) => {
    cfg.modResults.contents = addAdMobPodfileEnv(cfg.modResults.contents);
    return cfg;
  });
  config = withInfoPlist(config, (cfg) => {
    cfg.modResults = setAdMobInfoPlist(
      cfg.modResults,
      appIdFor(admob, 'ios'),
      delay,
      admob.skAdNetworkItems ?? []
    );
    return cfg;
  });
  return config;
};

export default createRunOncePlugin(withQartveloAds, pkg.name, pkg.version);
