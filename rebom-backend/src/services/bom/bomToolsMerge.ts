/**
 * Carries the producing tools of the input BOMs into a merged BOM.
 *
 * `rearm bomutils merge-boms` writes a fresh `metadata` for the merged document, so the tools
 * that produced each input (cdxgen, cyclonedx-maven-plugin, a scanner, ...) were lost: an
 * aggregated SBOM named rebom alone as its producer. Which tools generated the data is part of
 * what a reader of the merged document needs (CISA's 2025 minimum elements name the tool), so
 * the union of the inputs' tools is kept, de-duplicated, in the CycloneDX 1.5+ shape
 * (`tools: {components, services}`), or the legacy 1.4 array when the merged BOM is 1.4.
 *
 * ReARM's own entry (io.reliza / rearm) is not copied: `attachRebomToolToBom` adds the current
 * one after the merge, and an input's copy would name an older rebom. A copied tool drops its
 * `bom-ref`: refs are unique per document, and an input's tool ref may collide with a component
 * ref of another input once both share one merged document.
 */

type Tools = { components: any[]; services: any[] };

function isRearmTool(tool: any): boolean {
  return !!tool && tool.name === 'rearm' && tool.group === 'io.reliza';
}

function toolKey(tool: any): string {
  const part = (v: unknown) => (typeof v === 'string' ? v.trim().toLowerCase() : '');
  return [part(tool.group ?? tool.vendor), part(tool.name), part(tool.version)].join('|');
}

/** A legacy (CycloneDX 1.4) tool entry as a 1.5+ tool component. */
function legacyToolAsComponent(tool: any): any {
  const component: any = { type: 'application', name: tool.name };
  if (tool.vendor) component.group = tool.vendor;
  if (tool.version) component.version = tool.version;
  if (Array.isArray(tool.hashes) && tool.hashes.length) component.hashes = tool.hashes;
  if (Array.isArray(tool.externalReferences) && tool.externalReferences.length) {
    component.externalReferences = tool.externalReferences;
  }
  return component;
}

/** A 1.5+ tool component as a legacy (CycloneDX 1.4) tool entry. */
function componentAsLegacyTool(component: any): any {
  const tool: any = { name: component.name };
  if (component.group) tool.vendor = component.group;
  if (component.version) tool.version = component.version;
  if (Array.isArray(component.hashes) && component.hashes.length) tool.hashes = component.hashes;
  if (Array.isArray(component.externalReferences) && component.externalReferences.length) {
    tool.externalReferences = component.externalReferences;
  }
  return tool;
}

/** The tools a BOM names, in the 1.5+ shape, whichever shape the BOM uses. */
export function toolsOf(bom: any): Tools {
  const tools = bom?.metadata?.tools;
  if (!tools) return { components: [], services: [] };
  if (Array.isArray(tools)) {
    return { components: tools.filter((t: any) => t && t.name).map(legacyToolAsComponent), services: [] };
  }
  return {
    components: Array.isArray(tools.components) ? tools.components.filter((c: any) => c && c.name) : [],
    services: Array.isArray(tools.services) ? tools.services.filter((s: any) => s && s.name) : [],
  };
}

function isLegacySpec(specVersion: unknown): boolean {
  const v = typeof specVersion === 'string' && specVersion ? specVersion : '1.4';
  return parseFloat(v.split('.').slice(0, 2).join('.')) < 1.5;
}

/**
 * Adds to `merged.metadata.tools` every tool the `inputs` name that `merged` does not name yet,
 * in input order, skipping ReARM's own entry. Mutates and returns `merged`.
 */
export function mergeToolsFromInputs(merged: any, inputs: any[]): any {
  if (!merged) return merged;
  if (!merged.metadata) merged.metadata = {};
  const current = toolsOf(merged);
  const seenComponents = new Set(current.components.map(toolKey));
  const seenServices = new Set(current.services.map(toolKey));
  const components = [...current.components];
  const services = [...current.services];
  for (const input of inputs || []) {
    const t = toolsOf(input);
    for (const c of t.components) {
      if (isRearmTool(c)) continue;
      const key = toolKey(c);
      if (seenComponents.has(key)) continue;
      seenComponents.add(key);
      const { 'bom-ref': _ref, ...copy } = c;
      // `type` is required on a 1.5+ component; a producer that left it out still named a tool.
      if (!copy.type) copy.type = 'application';
      components.push(copy);
    }
    for (const s of t.services) {
      if (isRearmTool(s)) continue;
      const key = toolKey(s);
      if (seenServices.has(key)) continue;
      seenServices.add(key);
      const { 'bom-ref': _ref, ...copy } = s;
      services.push(copy);
    }
  }
  if (isLegacySpec(merged.specVersion)) {
    // 1.4 has no tool services; the legacy array holds tool entries only.
    merged.metadata.tools = components.map(componentAsLegacyTool);
  } else {
    merged.metadata.tools = services.length ? { components, services } : { components };
  }
  return merged;
}
