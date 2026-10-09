import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  jest,
} from '@jest/globals';

jest.mock(
  '../NativeQartveloAds',
  () => require('../__fixtures__/fakeNative').nativeModuleMock
);

type Fake = typeof import('../__fixtures__/fakeNative').fake;
type Api = typeof import('../index').QartveloAds;

function spyWarn() {
  return jest.spyOn(console, 'warn').mockImplementation(() => {});
}

// Module state (the warned set, the kept subscription) is per module instance, so every test
// loads fresh copies of the package and of the fake native module.
let fake: Fake;
let QartveloAds: Api;
let warn: ReturnType<typeof spyWarn>;

beforeEach(() => {
  jest.resetModules();
  ({ fake } = require('../__fixtures__/fakeNative'));
  ({ QartveloAds } = require('../index'));
  require('../__fixtures__/platform').setPlatform('android');
  fake.reset();
  warn = spyWarn();
});

afterEach(() => {
  warn.mockRestore();
});

function issue(code: string, message: string, placementId = '') {
  return { type: 'setupIssue', placementId, error: { code, message } };
}

describe('setupIssue', () => {
  it('warns in development without any app listener', async () => {
    await QartveloAds.initialize({ appKey: 'app_x' });
    fake.emit(
      issue('package_mismatch', "This app key is registered for 'com.other'")
    );
    expect(warn).toHaveBeenCalledWith(
      "[QartveloAds] This app key is registered for 'com.other'"
    );
  });

  it('warns for an issue the SDK reports while initialize is still running', async () => {
    // The native subscription must be open before the native call starts: initializeSdk can emit
    // (for example a package mismatch from the first backend answer) before its promise settles.
    fake.native.initializeSdk.mockImplementationOnce(async () => {
      fake.emit(
        issue('package_mismatch', "This app key is registered for 'com.other'")
      );
    });
    await QartveloAds.initialize({ appKey: 'app_x' });
    expect(warn).toHaveBeenCalledTimes(1);
    expect(warn).toHaveBeenCalledWith(
      "[QartveloAds] This app key is registered for 'com.other'"
    );
  });

  it('keeps one native subscription after initialize in development, with no app listener', async () => {
    await QartveloAds.initialize({ appKey: 'app_x' });
    expect(fake.subscriberCount()).toBe(1);
  });

  it('warns once per code and placement', async () => {
    await QartveloAds.initialize({ appKey: 'app_x' });
    fake.emit(issue('unknown_placement', 'Create level_up', 'level_up'));
    fake.emit(issue('unknown_placement', 'Create level_up', 'level_up'));
    fake.emit(issue('unknown_placement', 'Create level_two', 'level_two'));
    expect(warn).toHaveBeenCalledTimes(2);
  });

  it('delivers issues to app listeners', () => {
    const received: unknown[] = [];
    QartveloAds.addListener('setupIssue', (event) => received.push(event));
    fake.emit(
      issue('format_mismatch', 'Use a banner placement', 'home_banner')
    );
    fake.emit(issue('package_mismatch', 'Wrong key'));
    fake.emit(issue('something_new', 'Ignored'));
    expect(received).toEqual([
      {
        type: 'setupIssue',
        code: 'format_mismatch',
        message: 'Use a banner placement',
        placementId: 'home_banner',
      },
      { type: 'setupIssue', code: 'package_mismatch', message: 'Wrong key' },
    ]);
  });

  it('does not warn in release builds', async () => {
    const globals = global as unknown as { __DEV__: boolean };
    const dev = globals.__DEV__;
    globals.__DEV__ = false;
    try {
      await QartveloAds.initialize({ appKey: 'app_x' });
      const received: unknown[] = [];
      QartveloAds.addListener('setupIssue', (event) => received.push(event));
      fake.emit(issue('package_mismatch', 'Wrong key'));
      expect(received).toHaveLength(1);
      expect(warn).not.toHaveBeenCalled();
    } finally {
      globals.__DEV__ = dev;
    }
  });

  it('holds no native subscription after initialize in release builds, with no app listener', async () => {
    const globals = global as unknown as { __DEV__: boolean };
    const dev = globals.__DEV__;
    globals.__DEV__ = false;
    try {
      await QartveloAds.initialize({ appKey: 'app_x' });
      expect(fake.subscriberCount()).toBe(0);
    } finally {
      globals.__DEV__ = dev;
    }
  });
});
