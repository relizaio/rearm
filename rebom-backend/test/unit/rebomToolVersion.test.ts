import { describe, it, expect, afterEach } from 'vitest';
import { readFileSync } from 'fs';
import { join } from 'path';
import * as m from '../../src/services/bom/bomProcessingService';
import {
  rebomToolVersion, createRebomToolObject, productVersionWarning, PRODUCT_VERSION_WARNING,
} from '../../src/services/bom/bomProcessingService';

const saved = { product: process.env.REARM_PRODUCT_VERSION, rebom: process.env.REBOM_VERSION };
function restoreEnv() {
  if (saved.product === undefined) delete process.env.REARM_PRODUCT_VERSION; else process.env.REARM_PRODUCT_VERSION = saved.product;
  if (saved.rebom === undefined) delete process.env.REBOM_VERSION; else process.env.REBOM_VERSION = saved.rebom;
}

describe('rebomToolVersion', () => {
  afterEach(restoreEnv);

  it('names the ReARM product version the deployment passes', () => {
    process.env.REARM_PRODUCT_VERSION = ' 26.10.28 ';
    expect(rebomToolVersion()).toBe('26.10.28');
    expect(createRebomToolObject('1.6').version).toBe('26.10.28');
    expect(createRebomToolObject('1.4').version).toBe('26.10.28');
  });

  for (const [label, value] of [['unset', undefined], ['blank', '  ']] as const) {
    it(`names no version, not rebom's own nor a constant, when the variable is ${label}`, () => {
      if (value === undefined) delete process.env.REARM_PRODUCT_VERSION; else process.env.REARM_PRODUCT_VERSION = value;
      process.env.REBOM_VERSION = '0.24.1';
      expect(rebomToolVersion()).toBeUndefined();
      for (const spec of ['1.4', '1.5', '1.6', '1.7', undefined]) {
        expect(Object.hasOwn(createRebomToolObject(spec as any), 'version')).toBe(false);
      }
      expect(createRebomToolObject('1.4')).toMatchObject({ vendor: 'io.reliza', name: 'rearm' });
    });
  }

  it('exports no fallback constant', () => {
    expect(m).not.toHaveProperty('REARM_PRODUCT_VERSION_FALLBACK');
  });

  it('carries no fixed ReARM version in its source', () => {
    const src = readFileSync(join(__dirname, '../../src/services/bom/bomProcessingService.ts'), 'utf8');
    expect(src).not.toContain('26.08.95');
  });
});

describe('productVersionWarning', () => {
  afterEach(restoreEnv);

  it('returns the fixed warning when the variable is unset', () => {
    delete process.env.REARM_PRODUCT_VERSION;
    expect(productVersionWarning()).toBe(PRODUCT_VERSION_WARNING);
    expect(PRODUCT_VERSION_WARNING).toBe('REARM_PRODUCT_VERSION is not set: SBOMs this service writes will name ReARM without a version. Set it to the ReARM version of this deployment (the Pro chart sets it from appVersion).');
  });

  it('returns the fixed warning when the variable is blank', () => {
    process.env.REARM_PRODUCT_VERSION = '  ';
    expect(productVersionWarning()).toBe(PRODUCT_VERSION_WARNING);
  });

  it('returns nothing when the variable is set', () => {
    process.env.REARM_PRODUCT_VERSION = '26.10.28';
    expect(productVersionWarning()).toBeUndefined();
  });
});
