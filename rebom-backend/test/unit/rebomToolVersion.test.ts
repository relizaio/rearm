import { describe, it, expect, afterEach } from 'vitest';
import { rebomToolVersion, createRebomToolObject, REARM_PRODUCT_VERSION_FALLBACK } from '../../src/services/bom/bomProcessingService';

describe('rebomToolVersion', () => {
  const saved = { product: process.env.REARM_PRODUCT_VERSION, rebom: process.env.REBOM_VERSION };
  afterEach(() => {
    if (saved.product === undefined) delete process.env.REARM_PRODUCT_VERSION; else process.env.REARM_PRODUCT_VERSION = saved.product;
    if (saved.rebom === undefined) delete process.env.REBOM_VERSION; else process.env.REBOM_VERSION = saved.rebom;
  });

  it('names the ReARM product version the deployment passes', () => {
    process.env.REARM_PRODUCT_VERSION = ' 26.10.28 ';
    expect(rebomToolVersion()).toBe('26.10.28');
    expect(createRebomToolObject('1.6').version).toBe('26.10.28');
    expect(createRebomToolObject('1.4').version).toBe('26.10.28');
  });

  it('falls back to the fixed ReARM version, not rebom\'s own, when the deployment passes none', () => {
    delete process.env.REARM_PRODUCT_VERSION;
    process.env.REBOM_VERSION = '0.24.1';
    expect(REARM_PRODUCT_VERSION_FALLBACK).toBe('26.08.95');
    expect(rebomToolVersion()).toBe('26.08.95');
    process.env.REARM_PRODUCT_VERSION = '  ';
    expect(rebomToolVersion()).toBe('26.08.95');
  });
});
