import { describe, it, expect } from 'vitest';
import { mergeToolsFromInputs, toolsOf } from '../../src/services/bom/bomToolsMerge';

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

  it('drops a copied tool\'s bom-ref so refs stay unique in the merged document', () => {
    const merged = bom('1.6', { components: [] });
    mergeToolsFromInputs(merged, [bom('1.6', { components: [cdxgen] })]);
    expect(merged.metadata.tools.components[0]['bom-ref']).toBeUndefined();
    expect(cdxgen['bom-ref']).toBe('pkg:npm/@cdxgen/cdxgen@13.2.0'); // the input is not mutated
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
