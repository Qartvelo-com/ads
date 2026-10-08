# Qartvelo Ads React Native

| Path | Contents |
|---|---|
| `packages/react-native-qartvelo-ads/` | `@qartvelo/react-native-ads`: TurboModule + Fabric banner bridging to the Qartvelo Ads Android and iOS SDKs |
| `example/` | Example app (`com.qartvelo.rnexample`, React Native 0.87) consuming the local package |

Quick start (backend running on the host at port 8000, SDK installed with
`cd ../android && ./gradlew publishToMavenLocal`):

```sh
cd packages/react-native-qartvelo-ads && npm install && npm run build
cd ../../example && npm install
npx react-native start            # Metro
npx react-native run-android      # build, install and launch on the emulator
```

See the [React Native guide](https://developers.qartvelo.com/react-native/installation/).

## iOS example

```sh
cd packages/react-native-qartvelo-ads && npm install && npm run build
cd ../../example && npm install
cd ios && QARTVELO_ADS_ADMOB_ENABLED=true pod install && cd ..
npm start
# In a second terminal:
npm run ios
```

The iOS example defaults to the production HTTPS backend and public test ads. The Android example
keeps the local backend URL. All sample requests default to `testMode: true`.
