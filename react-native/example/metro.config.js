const path = require('path');
const { getDefaultConfig, mergeConfig } = require('@react-native/metro-config');

/**
 * The example consumes the local package (`file:../packages/react-native-qartvelo-ads`, a symlink). Metro
 * watches the package sources and resolves react / react-native only from this app, so the
 * package's own dev copies are never bundled twice.
 *
 * @type {import('@react-native/metro-config').MetroConfig}
 */
const packageRoot = path.resolve(__dirname, '../packages/react-native-qartvelo-ads');
const escape = value => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

const config = {
  watchFolders: [packageRoot],
  resolver: {
    nodeModulesPaths: [path.resolve(__dirname, 'node_modules')],
    blockList: [
      new RegExp(
        `^${escape(
          path.join(packageRoot, 'node_modules'),
        )}\\/(react|react-native)\\/.*$`,
      ),
    ],
    extraNodeModules: {
      react: path.resolve(__dirname, 'node_modules/react'),
      'react-native': path.resolve(__dirname, 'node_modules/react-native'),
    },
    // Use the package's TypeScript sources directly (see its package.json "exports").
    unstable_conditionNames: ['react-native', 'qartvelo-react-native-source'],
  },
};

module.exports = mergeConfig(getDefaultConfig(__dirname), config);
