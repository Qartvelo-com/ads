/**
 * @jest-environment node
 */
import { describe, expect, it } from '@jest/globals';
import { readFileSync } from 'fs';
import { isAbsolute, join } from 'path';

type PackageJson = {
  exports: Record<string, unknown>;
  files: string[];
};

const packageRoot = join(__dirname, '..', '..', '..');

const pkg = JSON.parse(
  readFileSync(join(packageRoot, 'package.json'), 'utf8')
) as PackageJson;

type ScriptPhase = {
  name: string;
  path?: string;
  script?: string;
  execution_position: string;
  input_files: string[];
};

type NativeConfig = {
  dependency: {
    platforms: { ios: { scriptPhases: ScriptPhase[] } };
  };
};

const nativeConfig = require('../../../react-native.config.js') as NativeConfig;

describe('package.json packaging for the Expo config plugin', () => {
  // Expo resolves "<package>/app.plugin.js" through the package exports map, so a
  // package that defines "exports" must export the plugin entry explicitly.
  it('exports app.plugin.js so Expo can resolve the plugin from an installed package', () => {
    expect(pkg.exports['./app.plugin.js']).toBe('./app.plugin.js');
  });

  it('keeps the existing entry points', () => {
    expect(pkg.exports['./package.json']).toBe('./package.json');
    expect(pkg.exports['.']).toBeDefined();
  });

  it('ships the plugin entry, its build output and the SKAdNetwork list', () => {
    expect(pkg.files).toContain('app.plugin.js');
    expect(pkg.files).toContain('plugin/build');
    expect(pkg.files).toContain('plugin/skadnetwork.json');
  });
});

describe('iOS script phase for bare React Native apps', () => {
  // CocoaPods writes the phase content into the host's project.pbxproj, which bare React Native teams
  // commit. A path to this package on the developer's machine in that file churns on every other machine
  // and breaks builds that reuse a committed Pods directory without running pod install again.
  const phases = nativeConfig.dependency.platforms.ios.scriptPhases;
  const phase = phases[0] as ScriptPhase;
  const body = phase.path
    ? readFileSync(join(packageRoot, phase.path), 'utf8')
    : (phase.script ?? '');

  it('declares exactly the AdMob configuration phase after compile', () => {
    expect(phases).toHaveLength(1);
    expect(phase.name).toBe('[QartveloAds] AdMob configuration');
    expect(phase.execution_position).toBe('after_compile');
    expect(phase.input_files).toEqual([
      '$(BUILT_PRODUCTS_DIR)/$(INFOPLIST_PATH)',
    ]);
  });

  it('points at a script file inside the package, not at an absolute path', () => {
    expect(phase.script).toBeUndefined();
    expect(typeof phase.path).toBe('string');
    expect(isAbsolute(phase.path as string)).toBe(false);
    expect(phase.path).toBe('./scripts/ios-config.sh');
  });

  it('writes no machine-specific path into the project file', () => {
    expect(body).not.toContain(packageRoot);
    expect(body).not.toMatch(/\/(Users|home|private|var)\//);
    expect(body).not.toContain(__dirname);
  });

  it('resolves the package at build time and runs the Ruby script', () => {
    expect(body).toContain('.xcode.env');
    expect(body).toContain('NODE_BINARY');
    expect(body).toContain('@qartvelo/react-native-ads/package.json');
    expect(body).toContain('scripts/ios-config.rb');
    expect(body).toMatch(/error: @qartvelo\/react-native-ads: /);
  });

  it('ships the shell script and the Ruby script it runs', () => {
    expect(pkg.files).toContain('scripts/ios-config.sh');
    expect(pkg.files).toContain('scripts/ios-config.rb');
    expect(pkg.files).toContain('react-native.config.js');
  });
});

describe('Android manifests for the AdMob App ID from app.json', () => {
  // A bare app that already declares DELAY_APP_MEASUREMENT_INIT in its own manifest and adds the app.json
  // key without delayAppMeasurementInit must not hit a manifest merger conflict: the library declares the
  // flag only when the option is true (like the Expo plugin, which writes it only when true).
  const DELAY = 'com.google.android.gms.ads.DELAY_APP_MEASUREMENT_INIT';
  const APP_ID = 'com.google.android.gms.ads.APPLICATION_ID';
  const read = (name: string) =>
    readFileSync(
      join(packageRoot, 'android', 'src', name, 'AndroidManifest.xml'),
      'utf8'
    );

  it('declares the App ID and no measurement delay flag by default', () => {
    const manifest = read('admob');
    expect(manifest).toContain(APP_ID);
    expect(manifest).toContain('${qartveloAdmobAppId}');
    expect(manifest).not.toContain(DELAY);
    expect(manifest).not.toContain('qartveloDelayMeasurement');
  });

  it('declares the flag as true only in the delay variant', () => {
    const manifest = read('admobDelay');
    expect(manifest).toContain(APP_ID);
    expect(manifest).toMatch(new RegExp(`${DELAY}"\\s+android:value="true"`));
  });
});
