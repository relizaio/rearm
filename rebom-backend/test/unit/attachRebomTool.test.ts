import { describe, it, expect, afterEach } from 'vitest';
import { attachRebomToolToBom } from '../../src/services/bom/bomProcessingService';

const cdxgen = { type: 'application', group: '@cdxgen', name: 'cdxgen', version: '13.2.0' };

describe('attachRebomToolToBom', () => {
  const saved = process.env.REARM_PRODUCT_VERSION;
  afterEach(() => {
    if (saved === undefined) delete process.env.REARM_PRODUCT_VERSION; else process.env.REARM_PRODUCT_VERSION = saved;
  });

  it('replaces a stale ReARM entry (an older rebom wrote it without a version) with the current one', () => {
    process.env.REARM_PRODUCT_VERSION = '26.10.28';
    const bom: any = { specVersion: '1.6', metadata: { tools: { components: [
      cdxgen, { type: 'application', group: 'io.reliza', name: 'rearm' },
    ] } } };
    attachRebomToolToBom(bom);
    const ours = bom.metadata.tools.components.filter((t: any) => t.group === 'io.reliza');
    expect(ours).toHaveLength(1);
    expect(ours[0].version).toBe('26.10.28');
    expect(bom.metadata.tools.components[0]).toEqual(cdxgen);
  });

  it('stays idempotent: attaching twice leaves one ReARM entry', () => {
    const bom: any = { specVersion: '1.6', metadata: {} };
    attachRebomToolToBom(attachRebomToolToBom(bom));
    expect(bom.metadata.tools.components.filter((t: any) => t.name === 'rearm')).toHaveLength(1);
  });

  it('replaces the historic rebom entry keyed by vendor in a 1.4 tools array', () => {
    process.env.REARM_PRODUCT_VERSION = '26.10.28';
    const bom: any = { specVersion: '1.4', metadata: { tools: [
      { vendor: 'io.reliza', name: 'rebom', version: '0.20.0' },
      { vendor: 'aquasecurity', name: 'trivy', version: '0.50.0' },
    ] } };
    attachRebomToolToBom(bom);
    expect(bom.metadata.tools.map((t: any) => `${t.name}@${t.version}`)).toEqual(['trivy@0.50.0', 'rearm@26.10.28']);
  });

  it('leaves a lookalike tool from another namespace alone', () => {
    const bom: any = { specVersion: '1.6', metadata: { tools: { components: [
      { type: 'application', group: 'com.example', name: 'rearm', version: '9.9.9' },
    ] } } };
    attachRebomToolToBom(bom);
    expect(bom.metadata.tools.components.map((t: any) => t.group)).toEqual(['com.example', 'io.reliza']);
  });
});
