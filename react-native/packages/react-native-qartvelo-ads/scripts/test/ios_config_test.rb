# frozen_string_literal: true

# Runs scripts/ios-config.rb against temporary projects. macOS only (PlistBuddy, plutil):
#   ruby scripts/test/ios_config_test.rb
require 'fileutils'
require 'json'
require 'minitest/autorun'
require 'open3'
require 'tmpdir'

class IosConfigTest < Minitest::Test
  SCRIPT = File.expand_path('../ios-config.rb', __dir__)
  IOS = 'ca-app-pub-3940256099942544~1458002511'
  OTHER = 'ca-app-pub-1111111111111111~2222222222'
  KEY = '@qartvelo/react-native-ads'
  PLIST = <<~PLIST
    <?xml version="1.0" encoding="UTF-8"?>
    <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
    <plist version="1.0"><dict><key>CFBundleName</key><string>App</string></dict></plist>
  PLIST

  def setup
    @dir = Dir.mktmpdir
    @project = File.join(@dir, 'ios')
    @products = File.join(@dir, 'build')
    FileUtils.mkdir_p([@project, File.join(@products, 'App.app')])
    @plist = File.join(@products, 'App.app', 'Info.plist')
    @source_plist = File.join(@project, 'App', 'Info.plist')
    FileUtils.mkdir_p(File.dirname(@source_plist))
    [@plist, @source_plist].each { |path| File.write(path, PLIST) }
  end

  def teardown
    FileUtils.remove_entry(@dir)
  end

  def write_app_json(json)
    File.write(File.join(@dir, 'app.json'), JSON.generate(json))
  end

  # A nil value in overrides unsets that variable for the script.
  def run_script(overrides = {})
    env = {
      'PROJECT_DIR' => @project, 'SRCROOT' => @project, 'INFOPLIST_FILE' => 'App/Info.plist',
      'BUILT_PRODUCTS_DIR' => @products, 'INFOPLIST_PATH' => 'App.app/Info.plist'
    }.merge(overrides)
    Open3.capture2e(env, 'ruby', SCRIPT)
  end

  def read(key, plist = @plist)
    output, status = Open3.capture2e('/usr/libexec/PlistBuddy', '-c', "Print :#{key}", plist)
    status.success? ? output.strip : nil
  end

  def sk_identifiers
    json, status = Open3.capture2('/usr/bin/plutil', '-extract', 'SKAdNetworkItems', 'json', '-o', '-', @plist)
    status.success? ? JSON.parse(json).map { |item| item['SKAdNetworkIdentifier'] } : []
  end

  def test_writes_app_id_delay_and_skadnetwork_items
    write_app_json(KEY => { 'admob' => { 'iosAppId' => IOS, 'delayAppMeasurementInit' => true, 'skAdNetworkItems' => ['example123.skadnetwork'] } })
    output, status = run_script
    assert status.success?, output
    assert_equal IOS, read('GADApplicationIdentifier')
    assert_equal 'true', read('GADDelayAppMeasurementInit')
    ids = sk_identifiers
    assert_includes ids, 'cstr6suwn9.skadnetwork'
    assert_includes ids, 'example123.skadnetwork'
    assert_equal ids.uniq, ids
  end

  def test_running_twice_adds_nothing_new
    write_app_json(KEY => { 'admob' => { 'iosAppId' => IOS } })
    run_script
    first = sk_identifiers
    output, status = run_script
    assert status.success?, output
    assert_equal first, sk_identifiers
  end

  def test_same_app_id_in_the_source_plist_passes_and_different_one_fails
    system('/usr/libexec/PlistBuddy', '-c', "Add :GADApplicationIdentifier string #{IOS}", @source_plist)
    write_app_json(KEY => { 'admob' => { 'iosAppId' => IOS } })
    output, status = run_script
    assert status.success?, output
    assert_equal IOS, read('GADApplicationIdentifier')
    write_app_json(KEY => { 'admob' => { 'iosAppId' => OTHER } })
    output, status = run_script
    refute status.success?
    assert_match(/Info\.plist already sets GADApplicationIdentifier to #{Regexp.escape(IOS)}, but admob\.iosAppId in app\.json is #{Regexp.escape(OTHER)}\. Keep one AdMob App ID\./, output)
  end

  def test_an_earlier_injection_in_the_built_plist_is_replaced_not_reported_as_a_conflict
    write_app_json(KEY => { 'admob' => { 'iosAppId' => IOS } })
    output, status = run_script
    assert status.success?, output
    assert_equal IOS, read('GADApplicationIdentifier')
    # The developer changes the App ID in app.json and builds incrementally: the built plist still holds the
    # previous injection, while the source plist has no GADApplicationIdentifier.
    write_app_json(KEY => { 'admob' => { 'iosAppId' => OTHER } })
    output, status = run_script
    assert status.success?, output
    assert_equal OTHER, read('GADApplicationIdentifier')
    assert_nil read('GADApplicationIdentifier', @source_plist)
  end

  def test_the_conflict_check_is_skipped_without_a_source_plist
    system('/usr/libexec/PlistBuddy', '-c', "Add :GADApplicationIdentifier string #{OTHER}", @plist)
    write_app_json(KEY => { 'admob' => { 'iosAppId' => IOS } })
    [{ 'INFOPLIST_FILE' => nil }, { 'INFOPLIST_FILE' => 'Missing/Info.plist' }].each do |overrides|
      output, status = run_script(overrides)
      assert status.success?, output
      assert_equal IOS, read('GADApplicationIdentifier')
      system('/usr/libexec/PlistBuddy', '-c', "Set :GADApplicationIdentifier #{OTHER}", @plist)
    end
  end

  def test_invalid_app_id_fails_with_the_key_name
    write_app_json(KEY => { 'admob' => { 'iosAppId' => 'ca-app-pub-123/456' } })
    output, status = run_script
    refute status.success?
    assert_match(/admob\.iosAppId/, output)
  end

  def test_does_nothing_without_app_json_or_the_key
    _, status = run_script
    assert status.success?
    write_app_json('name' => 'App')
    _, status = run_script
    assert status.success?
    assert_nil read('GADApplicationIdentifier')
  end

  def test_expo_projects_without_the_key_are_left_alone
    write_app_json('expo' => { 'name' => 'App', 'plugins' => [[KEY, { 'admob' => { 'iosAppId' => IOS } }]] })
    _, status = run_script
    assert status.success?
    assert_nil read('GADApplicationIdentifier')
  end

  def test_expo_projects_with_the_top_level_key_fail
    write_app_json('expo' => { 'name' => 'App' }, KEY => { 'admob' => { 'iosAppId' => IOS } })
    output, status = run_script
    refute status.success?
    assert_match(/expo\.plugins/, output)
  end
end
