const path = require('path');

/**
 * Android and iOS autolink their native implementations. On iOS, a build phase writes the AdMob keys
 * from the app's app.json ("@qartvelo/react-native-ads" -> admob) into the built Info.plist; it does
 * nothing for apps without that key (Expo apps use the config plugin).
 */
module.exports = {
  dependency: {
    platforms: {
      ios: {
        scriptPhases: [
          {
            name: '[QartveloAds] AdMob configuration',
            script: `ruby "${path.join(__dirname, 'scripts', 'ios-config.rb')}"`,
            execution_position: 'after_compile',
            input_files: ['$(BUILT_PRODUCTS_DIR)/$(INFOPLIST_PATH)'],
          },
        ],
      },
    },
  },
};
