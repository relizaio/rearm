import { describe, it, expect } from 'vitest';
import { mergeToolsFromInputs, mergeLifecyclesFromInputs, toolsOf } from '../../src/services/bom/bomToolsMerge';

const cdxgen = { type: 'application', group: '@cdxgen', name: 'cdxgen', version: '13.2.0', 'bom-ref': 'pkg:npm/@cdxgen/cdxgen@13.2.0' };
const mavenPlugin = { type: 'library', group: 'org.cyclonedx', name: 'cyclonedx-maven-plugin', version: '2.9.1' };
const rearm = { type: 'application', group: 'io.reliza', name: 'rearm', version: '0.24.1' };

function bom(specVersion: string, tools: any) {
  return { bomFormat: 'CycloneDX', specVersion, metadata: { tools }, components: [] };
}

describe('mergeToolsFromInputs', () => {
  it('keeps the union of the inputs\' tool components, de-duplicated, in input order', () => {
    const merged = bom('1.6', { components: [] });
    const inputs = [
      bom('1.6', { components: [mavenPlugin, cdxgen] }),
      bom('1.6', { components: [{ ...cdxgen }] }),
    ];
    mergeToolsFromInputs(merged, inputs);
    expect(merged.metadata.tools.components.map((t: any) => `${t.group}/${t.name}@${t.version}`))
      .toEqual(['org.cyclonedx/cyclonedx-maven-plugin@2.9.1', '@cdxgen/cdxgen@13.2.0']);
  });

  it('treats the same tool at another version as a different tool', () => {
    const merged = bom('1.6', { components: [] });
    mergeToolsFromInputs(merged, [
      bom('1.6', { components: [cdxgen] }),
      bom('1.6', { components: [{ ...cdxgen, version: '12.0.0' }] }),
    ]);
    expect(merged.metadata.tools.components.map((t: any) => t.version)).toEqual(['13.2.0', '12.0.0']);
  });

  it('does not copy ReARM\'s own entry, which attachRebomToolToBom adds fresh', () => {
    const merged = bom('1.6', { components: [] });
    mergeToolsFromInputs(merged, [bom('1.6', { components: [rearm, cdxgen] })]);
    expect(merged.metadata.tools.components.map((t: any) => t.name)).toEqual(['cdxgen']);
  });

  it('keeps a copied tool\'s bom-ref, which component evidence points at', () => {
    const merged = bom('1.6', { components: [] });
    mergeToolsFromInputs(merged, [bom('1.6', { components: [cdxgen] })]);
    expect(merged.metadata.tools.components[0]['bom-ref']).toBe('pkg:npm/@cdxgen/cdxgen@13.2.0');
  });

  it('gives a copied tool a fresh bom-ref when the merged document already uses its ref', () => {
    const merged: any = bom('1.6', { components: [] });
    merged.components = [{ type: 'library', name: 'cdxgen', 'bom-ref': 'pkg:npm/@cdxgen/cdxgen@13.2.0' }];
    mergeToolsFromInputs(merged, [bom('1.6', { components: [cdxgen] })]);
    expect(merged.metadata.tools.components[0]['bom-ref']).toBe('pkg:npm/@cdxgen/cdxgen@13.2.0-2');
    expect(cdxgen['bom-ref']).toBe('pkg:npm/@cdxgen/cdxgen@13.2.0'); // the input is not mutated
  });

  it('points the second input\'s evidence at its tool\'s fresh ref when two inputs\' tools share a ref', () => {
    const cites = (ref: string, tool: string) => ({ name: ref, 'bom-ref': ref,
      evidence: { identity: [{ field: 'purl', confidence: 1, tools: [tool] }] } });
    const toolRef = 'tool-1';
    const a = bom('1.6', { components: [{ ...cdxgen, 'bom-ref': toolRef }] });
    a.components = [cites('pkg:npm/a@1', toolRef)];
    const b = bom('1.6', { components: [{ ...mavenPlugin, 'bom-ref': toolRef }] });
    b.components = [cites('pkg:maven/b@1', toolRef)];
    const merged: any = bom('1.6', { components: [] });
    merged.components = [cites('pkg:npm/a@1', toolRef), cites('pkg:maven/b@1', toolRef)];
    mergeToolsFromInputs(merged, [a, b]);
    expect(merged.metadata.tools.components.map((t: any) => `${t.name}=${t['bom-ref']}`))
      .toEqual(['cdxgen=tool-1', 'cyclonedx-maven-plugin=tool-1-2']);
    expect(merged.components.map((c: any) => c.evidence.identity[0].tools[0])).toEqual(['tool-1', 'tool-1-2']);
  });

  it('points evidence at the kept entry when two inputs name the same tool under different refs', () => {
    const cites = (ref: string, tool: string) => ({ name: ref, 'bom-ref': ref,
      evidence: { identity: [{ field: 'purl', confidence: 1, tools: [tool] }] } });
    const a = bom('1.6', { components: [{ ...cdxgen, 'bom-ref': 'tool-a' }] });
    a.components = [cites('pkg:npm/a@1', 'tool-a')];
    const b = bom('1.6', { components: [{ ...cdxgen, 'bom-ref': 'tool-b' }] });
    b.components = [cites('pkg:npm/b@1', 'tool-b')];
    const merged: any = bom('1.6', { components: [] });
    merged.components = [cites('pkg:npm/a@1', 'tool-a'), cites('pkg:npm/b@1', 'tool-b')];
    mergeToolsFromInputs(merged, [a, b]);
    expect(merged.metadata.tools.components.map((t: any) => t['bom-ref'])).toEqual(['tool-a']);
    expect(merged.components.map((c: any) => c.evidence.identity[0].tools[0])).toEqual(['tool-a', 'tool-a']);
  });

  it('gives the kept entry the duplicate\'s ref when the kept one had none', () => {
    const { 'bom-ref': _, ...cdxgenNoRef } = cdxgen;
    const merged: any = bom('1.6', { components: [] });
    const b = bom('1.6', { components: [{ ...cdxgen, 'bom-ref': 'tool-b' }] });
    b.components = [{ name: 'x', 'bom-ref': 'pkg:npm/x@1', evidence: { identity: [{ field: 'purl', confidence: 1, tools: ['tool-b'] }] } }];
    merged.components = structuredClone(b.components);
    mergeToolsFromInputs(merged, [bom('1.6', { components: [cdxgenNoRef] }), b]);
    expect(merged.metadata.tools.components.map((t: any) => t['bom-ref'])).toEqual(['tool-b']);
    expect(merged.components[0].evidence.identity[0].tools).toEqual(['tool-b']);
  });

  it('maps each cited ref once, so one input\'s rename is not renamed again by another', () => {
    const syft = { type: 'application', group: 'anchore', name: 'syft', version: '1.0.0' };
    const x = () => ({ name: 'x', 'bom-ref': 'pkg:npm/x@1',
      evidence: { identity: [{ field: 'purl', confidence: 1, tools: ['tool-2', 'tool-1'] }] } });
    const a = bom('1.6', { components: [{ ...cdxgen, 'bom-ref': 'tool-1' }] });
    const b = bom('1.6', { components: [{ ...cdxgen, 'bom-ref': 'tool-2' }, { ...syft, 'bom-ref': 'tool-1' }] });
    b.components = [x()];
    const merged: any = bom('1.6', { components: [] });
    merged.components = [x()];
    mergeToolsFromInputs(merged, [a, b]);
    expect(merged.metadata.tools.components.map((t: any) => `${t.name}=${t['bom-ref']}`))
      .toEqual(['cdxgen=tool-1', 'syft=tool-1-2']);
    expect(merged.components[0].evidence.identity[0].tools).toEqual(['tool-1', 'tool-1-2']);
  });

  it('repoints a component several inputs cite under the same duplicate ref for the same tool', () => {
    const x = () => ({ name: 'x', 'bom-ref': 'pkg:npm/x@1',
      evidence: { identity: [{ field: 'purl', confidence: 1, tools: ['ref-b'] }] } });
    const a = bom('1.6', { components: [{ ...cdxgen, 'bom-ref': 'ref-a' }] });
    const b = bom('1.6', { components: [{ ...cdxgen, 'bom-ref': 'ref-b' }] });
    b.components = [x()];
    const c = bom('1.6', { components: [{ ...cdxgen, 'bom-ref': 'ref-b' }] });
    c.components = [x()];
    const merged: any = bom('1.6', { components: [] });
    merged.components = [x()];
    mergeToolsFromInputs(merged, [a, b, c]);
    expect(merged.metadata.tools.components.map((t: any) => t['bom-ref'])).toEqual(['ref-a']);
    expect(merged.components[0].evidence.identity[0].tools).toEqual(['ref-a']);
  });

  it('lists a ref once when two cited refs map to the same kept tool', () => {
    const x = () => ({ name: 'x', 'bom-ref': 'pkg:npm/x@1',
      evidence: { identity: [{ field: 'purl', confidence: 1, tools: ['tool-1', 'tool-2'] }] } });
    const a = bom('1.6', { components: [{ ...cdxgen, 'bom-ref': 'tool-1' }] });
    const b = bom('1.6', { components: [{ ...cdxgen, 'bom-ref': 'tool-2' }] });
    b.components = [x()];
    const merged: any = bom('1.6', { components: [] });
    merged.components = [x()];
    mergeToolsFromInputs(merged, [a, b]);
    expect(merged.components[0].evidence.identity[0].tools).toEqual(['tool-1']);
  });

  it('leaves the evidence of a component both inputs cite under the clashing ref as it was', () => {
    const shared = { name: 'shared', 'bom-ref': 'pkg:npm/shared@1',
      evidence: { identity: [{ field: 'purl', confidence: 1, tools: ['tool-1'] }] } };
    const a = bom('1.6', { components: [{ ...cdxgen, 'bom-ref': 'tool-1' }] });
    a.components = [structuredClone(shared)];
    const b = bom('1.6', { components: [{ ...mavenPlugin, 'bom-ref': 'tool-1' }] });
    b.components = [structuredClone(shared)];
    const merged: any = bom('1.6', { components: [] });
    merged.components = [structuredClone(shared)];
    mergeToolsFromInputs(merged, [a, b]);
    expect(merged.components[0].evidence.identity[0].tools).toEqual(['tool-1']);
  });

  it('recognises rebom\'s own 1.4 entry, which carries group rather than vendor, and does not copy it', () => {
    const merged = bom('1.6', { components: [] });
    mergeToolsFromInputs(merged, [bom('1.4', [
      { type: 'application', group: 'io.reliza', name: 'rearm', version: '26.10.12' },
      { vendor: 'CycloneDX', name: 'cyclonedx-gomod', version: '1.9.0' },
    ])]);
    expect(merged.metadata.tools.components.map((t: any) => t.name)).toEqual(['cyclonedx-gomod']);
  });

  it('treats one tool named with and without a leading @ on its namespace as the same tool', () => {
    const merged = bom('1.6', { components: [] });
    mergeToolsFromInputs(merged, [
      bom('1.6', { components: [{ type: 'application', group: '@cyclonedx', name: 'cdxgen', version: '13.2.0' }] }),
      bom('1.4', [{ vendor: 'cyclonedx', name: 'cdxgen', version: '13.2.0' }]),
    ]);
    expect(merged.metadata.tools.components).toHaveLength(1);
    expect(merged.metadata.tools.components[0].group).toBe('@cyclonedx');
  });

  it('gives a tool component without a type the application type', () => {
    const merged = bom('1.6', { components: [] });
    mergeToolsFromInputs(merged, [bom('1.6', { components: [{ name: 'scanner', version: '1' }] })]);
    expect(merged.metadata.tools.components[0]).toEqual({ name: 'scanner', version: '1', type: 'application' });
  });

  it('reads legacy 1.4 tool arrays and tool services', () => {
    const merged = bom('1.6', { components: [] });
    mergeToolsFromInputs(merged, [
      bom('1.4', [{ vendor: 'CycloneDX', name: 'cyclonedx-gomod', version: '1.9.0' }]),
      bom('1.6', { components: [], services: [{ name: 'osv-scanner-api', version: '2' }] }),
    ]);
    expect(merged.metadata.tools.components).toEqual([
      { type: 'application', group: 'CycloneDX', name: 'cyclonedx-gomod', version: '1.9.0' },
    ]);
    expect(merged.metadata.tools.services).toEqual([{ name: 'osv-scanner-api', version: '2' }]);
  });

  it('writes the legacy array when the merged BOM is 1.4', () => {
    const merged = bom('1.4', []);
    mergeToolsFromInputs(merged, [bom('1.6', { components: [cdxgen] })]);
    expect(merged.metadata.tools).toEqual([{ vendor: '@cdxgen', name: 'cdxgen', version: '13.2.0' }]);
  });

  it('keeps tools the merged BOM already names and adds none twice', () => {
    const merged = bom('1.6', { components: [mavenPlugin] });
    mergeToolsFromInputs(merged, [bom('1.6', { components: [mavenPlugin, cdxgen] })]);
    expect(merged.metadata.tools.components.map((t: any) => t.name)).toEqual(['cyclonedx-maven-plugin', 'cdxgen']);
  });

  it('leaves a merged BOM with tool-less inputs with an empty components list', () => {
    const merged: any = { specVersion: '1.6' };
    mergeToolsFromInputs(merged, [bom('1.6', undefined), {}]);
    expect(merged.metadata.tools).toEqual({ components: [] });
    expect(toolsOf(undefined)).toEqual({ components: [], services: [] });
  });
});

describe('mergeLifecyclesFromInputs', () => {
  it('keeps the inputs\' lifecycles, de-duplicated by phase or name', () => {
    const merged: any = { specVersion: '1.6', metadata: {} };
    mergeLifecyclesFromInputs(merged, [
      { metadata: { lifecycles: [{ phase: 'build' }] } },
      { metadata: { lifecycles: [{ phase: 'build' }, { name: 'nightly', description: 'scheduled scan' }] } },
      { metadata: {} },
    ]);
    expect(merged.metadata.lifecycles).toEqual([{ phase: 'build' }, { name: 'nightly', description: 'scheduled scan' }]);
  });

  it('adds nothing for inputs without lifecycles, or to a 1.4 BOM', () => {
    const merged: any = { specVersion: '1.6', metadata: {} };
    mergeLifecyclesFromInputs(merged, [{ metadata: {} }]);
    expect(merged.metadata.lifecycles).toBeUndefined();
    const legacy: any = { specVersion: '1.4', metadata: {} };
    mergeLifecyclesFromInputs(legacy, [{ metadata: { lifecycles: [{ phase: 'build' }] } }]);
    expect(legacy.metadata.lifecycles).toBeUndefined();
  });
});
