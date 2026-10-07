/**
 * Autolinking: Android only. iOS has no QartveloAds SDK yet, so nothing is linked there and the
 * JavaScript API rejects with `unsupported_platform`.
 *
 * @type {import('@react-native-community/cli-types').UserDependencyConfig}
 */
module.exports = {
  dependency: {
    platforms: {
      ios: null,
    },
  },
};
