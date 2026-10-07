# Qartvelo Ads React Native

| Path | Contents |
|---|---|
| `packages/react-native-qartvelo-ads/` | `@qartvelo/react-native-ads`: TurboModule + Fabric banner bridging to the Qartvelo Ads Android SDK |
| `example/` | Example app (`com.qartvelo.rnexample`, React Native 0.87) consuming the local package |

Quick start (backend running on the host at port 8000, SDK installed with
`cd ../android && ./gradlew publishToMavenLocal`):

```sh
cd packages/react-native-qartvelo-ads && npm install && npm run build
cd ../../example && npm install
npx react-native start            # Metro
npx react-native run-android      # build, install and launch on the emulator
```

See [docs/react-native-integration.md](../docs/react-native-integration.md).
