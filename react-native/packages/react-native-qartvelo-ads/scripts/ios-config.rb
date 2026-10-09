# frozen_string_literal: true

# Xcode build phase of @qartvelo/react-native-ads, added to the app target by react-native.config.js.
# Bare React Native apps configure the AdMob fallback in app.json:
#   "@qartvelo/react-native-ads": { "admob": { "iosAppId": "ca-app-pub-...~...", ... } }
# This writes GADApplicationIdentifier, GADDelayAppMeasurementInit and SKAdNetworkItems into the
# built app's Info.plist on every build. Expo apps use the config plugin instead: there this script
# does nothing unless the top-level key is present, which is an error.
require 'json'
require 'open3'

PACKAGE = '@qartvelo/react-native-ads'
APP_ID = /\Aca-app-pub-\d{16}~\d{10}\z/.freeze
PLIST_BUDDY = '/usr/libexec/PlistBuddy'

def fail_build(message)
  warn "error: #{PACKAGE}: #{message}"
  exit 1
end

def plist_buddy(plist, command)
  output, status = Open3.capture2e(PLIST_BUDDY, '-c', command, plist)
  [output.strip, status.success?]
end

app_json = File.expand_path(File.join(ENV.fetch('PROJECT_DIR'), '..', 'app.json'))
exit 0 unless File.file?(app_json)

root = JSON.parse(File.read(app_json))
config = root.is_a?(Hash) ? root[PACKAGE] : nil
exit 0 if config.nil?
if root.key?('expo')
  fail_build("in an Expo project, configure the AdMob fallback with the plugin entry in expo.plugins, not with a top-level \"#{PACKAGE}\" key in app.json.")
end
admob = config.is_a?(Hash) ? config['admob'] : nil
exit 0 if admob.nil?
fail_build('"admob" in app.json must be an object.') unless admob.is_a?(Hash)

app_id = admob['iosAppId'].to_s.strip
unless app_id =~ APP_ID
  fail_build("admob.iosAppId in app.json must be an AdMob App ID like ca-app-pub-0000000000000000~0000000000 (found \"#{app_id}\").")
end

plist = File.join(ENV.fetch('BUILT_PRODUCTS_DIR'), ENV.fetch('INFOPLIST_PATH'))
existing, found = plist_buddy(plist, 'Print :GADApplicationIdentifier')
if found && !existing.empty? && existing != app_id
  fail_build("Info.plist already sets GADApplicationIdentifier to #{existing}, but admob.iosAppId in app.json is #{app_id}. Keep one AdMob App ID.")
end
plist_buddy(plist, 'Delete :GADApplicationIdentifier')
plist_buddy(plist, "Add :GADApplicationIdentifier string #{app_id}")
if admob['delayAppMeasurementInit'] == true
  plist_buddy(plist, 'Delete :GADDelayAppMeasurementInit')
  plist_buddy(plist, 'Add :GADDelayAppMeasurementInit bool true')
end

google = JSON.parse(File.read(File.join(__dir__, '..', 'plugin', 'skadnetwork.json')))
extra = Array(admob['skAdNetworkItems']).map { |id| id.to_s.strip }.reject(&:empty?)
json, status = Open3.capture2('/usr/bin/plutil', '-extract', 'SKAdNetworkItems', 'json', '-o', '-', plist)
existing_items = status.success? ? JSON.parse(json) : []
plist_buddy(plist, 'Add :SKAdNetworkItems array') unless status.success?
current = existing_items.map { |item| item.is_a?(Hash) ? item['SKAdNetworkIdentifier'] : nil }.compact
missing = (google + extra).uniq - current
missing.each_with_index do |id, offset|
  index = existing_items.length + offset
  plist_buddy(plist, "Add :SKAdNetworkItems:#{index} dict")
  plist_buddy(plist, "Add :SKAdNetworkItems:#{index}:SKAdNetworkIdentifier string #{id}")
end
puts "note: #{PACKAGE}: AdMob App ID and #{missing.length} new SKAdNetwork identifiers written to Info.plist"
