/**
 * Android and iOS autolink their native implementations. On iOS, a build phase writes the AdMob keys
 * from the app's app.json ("@qartvelo/react-native-ads" -> admob) into the built Info.plist; it does
 * nothing for apps without that key (Expo apps use the config plugin).
 *
 * The phase is a script file (`path`), not a command string: CocoaPods copies the file's text into the
 * app's project.pbxproj, so the text must hold no path of the machine that ran pod install. The script
 * finds the installed package at build time instead (see scripts/ios-config.sh).
 */
module.exports = {
  dependency: {
    platforms: {
      ios: {
        scriptPhases: [
          {
            name: '[QartveloAds] AdMob configuration',
            path: './scripts/ios-config.sh',
            execution_position: 'after_compile',
            input_files: ['$(BUILT_PRODUCTS_DIR)/$(INFOPLIST_PATH)'],
          },
        ],
      },
    },
  },
};
