/**
 * @jest-environment node
 */
import { describe, expect, it } from '@jest/globals';
import { readFileSync } from 'fs';
import { join } from 'path';

type PackageJson = {
  exports: Record<string, unknown>;
  files: string[];
};

const pkg = JSON.parse(
  readFileSync(join(__dirname, '..', '..', '..', 'package.json'), 'utf8')
) as PackageJson;

describe('package.json packaging for the Expo config plugin', () => {
  // Expo resolves "<package>/app.plugin.js" through the package exports map, so a
  // package that defines "exports" must export the plugin entry explicitly.
  it('exports app.plugin.js so Expo can resolve the plugin from an installed package', () => {
    expect(pkg.exports['./app.plugin.js']).toBe('./app.plugin.js');
  });

  it('keeps the existing entry points', () => {
    expect(pkg.exports['./package.json']).toBe('./package.json');
    expect(pkg.exports['.']).toBeDefined();
  });

  it('ships the plugin entry, its build output and the SKAdNetwork list', () => {
    expect(pkg.files).toContain('app.plugin.js');
    expect(pkg.files).toContain('plugin/build');
    expect(pkg.files).toContain('plugin/skadnetwork.json');
  });
});
