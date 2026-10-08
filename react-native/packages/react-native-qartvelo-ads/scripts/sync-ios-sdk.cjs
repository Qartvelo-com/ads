// Bundle the canonical native SDK with npm. Generated copies must never be edited by hand.
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '../../../..');
const target = path.resolve(__dirname, '../ios/sdk');
const source = path.join(root, 'ios/Sources');
if (!fs.existsSync(source)) {
  if (fs.existsSync(path.join(target, 'QartveloAds/QartveloAds.swift'))) process.exit(0);
  throw new Error('Native iOS SDK sources are missing; build from the SDK repository.');
}
fs.rmSync(target, { recursive: true, force: true });
for (const name of ['QartveloAds', 'QartveloAdsAdMob']) {
  fs.cpSync(path.join(source, name), path.join(target, name), { recursive: true });
}
// The npm pod compiles the adapter and core in one Swift module.
for (const name of fs.readdirSync(path.join(target, 'QartveloAdsAdMob'))) {
  if (!name.endsWith('.swift')) continue;
  const file = path.join(target, 'QartveloAdsAdMob', name);
  fs.writeFileSync(file, fs.readFileSync(file, 'utf8').replace(/^import QartveloAds\n/m, ''));
}
fs.copyFileSync(path.join(root, 'LICENSE'), path.join(target, 'LICENSE'));
