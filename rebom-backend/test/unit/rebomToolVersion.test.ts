import { describe, it, expect, afterEach } from 'vitest';
import * as fs from 'fs';
import { rebomToolVersion, createRebomToolObject } from '../../src/services/bom/bomProcessingService';

const pkgVersion = require('../../package.json').version;

describe('rebomToolVersion', () => {
  const saved = { product: process.env.REARM_PRODUCT_VERSION, rebom: process.env.REBOM_VERSION, npm: process.env.npm_package_version };
  afterEach(() => {
    if (saved.product === undefined) delete process.env.REARM_PRODUCT_VERSION; else process.env.REARM_PRODUCT_VERSION = saved.product;
    if (saved.rebom === undefined) delete process.env.REBOM_VERSION; else process.env.REBOM_VERSION = saved.rebom;
    if (saved.npm === undefined) delete process.env.npm_package_version; else process.env.npm_package_version = saved.npm;
  });

  it('names a version without npm_package_version, as a deployed image runs (node src/index.js)', () => {
    delete process.env.npm_package_version;
    delete process.env.REBOM_VERSION;
    delete process.env.REARM_PRODUCT_VERSION;
    const v = rebomToolVersion();
    expect(v).toBeTruthy();
    // outside the image there is no /app/version; then package.json answers
    if (!fs.existsSync('/app/version')) expect(v).toBe(pkgVersion);
    expect(createRebomToolObject('1.6').version).toBe(v);
  });

  it('prefers REBOM_VERSION over the image and package versions', () => {
    delete process.env.REARM_PRODUCT_VERSION;
    process.env.REBOM_VERSION = '9.9.9';
    expect(rebomToolVersion()).toBe('9.9.9');
    expect(createRebomToolObject('1.6').version).toBe('9.9.9');
  });

  it('names the ReARM product version when the deployment passes it, ahead of rebom\'s own', () => {
    process.env.REARM_PRODUCT_VERSION = '26.10.28';
    process.env.REBOM_VERSION = '9.9.9';
    expect(rebomToolVersion()).toBe('26.10.28');
    expect(createRebomToolObject('1.6').version).toBe('26.10.28');
  });
});
