#!/bin/sh
# Build phase of @qartvelo/react-native-ads for bare React Native apps (declared in react-native.config.js).
# CocoaPods copies this file's text into the app's project.pbxproj, which teams commit, so it must not
# name a path on the machine that ran pod install: the package is located here, at build time.
set -e

# React Native keeps NODE_BINARY in ios/.xcode.env (and optionally ios/.xcode.env.local) next to Pods.
if [ -f "$PODS_ROOT/../.xcode.env" ]; then
  . "$PODS_ROOT/../.xcode.env"
fi
if [ -f "$PODS_ROOT/../.xcode.env.local" ]; then
  . "$PODS_ROOT/../.xcode.env.local"
fi

NODE="${NODE_BINARY:-node}"
if ! command -v "$NODE" > /dev/null 2>&1; then
  echo "error: @qartvelo/react-native-ads: cannot run node (\"$NODE\"). Set NODE_BINARY in ios/.xcode.env or ios/.xcode.env.local." >&2
  exit 1
fi

PKG=$("$NODE" -p "require('path').dirname(require.resolve('@qartvelo/react-native-ads/package.json',{paths:[process.argv[1]]}))" "$PROJECT_DIR" 2> /dev/null) || PKG=""
if [ -z "$PKG" ] || [ ! -f "$PKG/scripts/ios-config.rb" ]; then
  echo "error: @qartvelo/react-native-ads: cannot find the package from \"$PROJECT_DIR\". Run npm install (or yarn) in the app and pod install again." >&2
  exit 1
fi

ruby "$PKG/scripts/ios-config.rb"
