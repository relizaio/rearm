import { isRearmToolEntry as isRearmTool } from './bomProcessingService';

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
 * one after the merge, and an input's copy would name an older rebom. A copied tool keeps its
 * `bom-ref`, because component evidence points at it (`evidence.identity[].tools`). Refs are
 * unique per document, so when the ref is already taken the tool gets a fresh one, and the
 * evidence of that input's components is pointed at it.
 */

type Tools = { components: any[]; services: any[] };


/**
 * Identity of a tool across inputs and formats: namespace, name and version, case-insensitive.
 * A leading `@` on the namespace is ignored, so cdxgen named by one input as group `@cyclonedx`
 * and by another (a 1.4 vendor) as `cyclonedx` is one tool.
 */
function toolKey(tool: any): string {
  const part = (v: unknown) => (typeof v === 'string' ? v.trim().toLowerCase() : '');
  const namespace = part(tool.group ?? tool.vendor).replace(/^@/, '');
  return [namespace, part(tool.name), part(tool.version)].join('|');
}

/** A legacy (CycloneDX 1.4) tool entry as a 1.5+ tool component. */
function legacyToolAsComponent(tool: any): any {
  const component: any = { type: 'application', name: tool.name };
  // vendor is the 1.4 field; group as well, because rebom wrote its own 1.4 entry with group,
  // and dropping it made that entry unrecognisable as ReARM's (a second one was added).
  const namespace = tool.vendor ?? tool.group;
  if (namespace) component.group = namespace;
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
/** Every bom-ref already used anywhere in a document. */
function bomRefsIn(node: any, into: Set<string>): Set<string> {
  if (Array.isArray(node)) {
    for (const v of node) bomRefsIn(v, into);
  } else if (node && typeof node === 'object') {
    if (typeof node['bom-ref'] === 'string') into.add(node['bom-ref']);
    for (const v of Object.values(node)) bomRefsIn(v, into);
  }
  return into;
}

/**
 * A copy of a tool entry with its bom-ref, or a fresh ref when that one is already taken in the
 * merged document; a rename is recorded in `renamed` (old ref -> new ref).
 */
function copyTool(tool: any, usedRefs: Set<string>, renamed: Map<string, string>): any {
  const { 'bom-ref': ref, ...copy } = tool;
  if (typeof ref !== 'string' || !ref) return copy;
  let fresh = ref;
  for (let n = 2; usedRefs.has(fresh); n++) fresh = `${ref}-${n}`;
  usedRefs.add(fresh);
  if (fresh !== ref) renamed.set(ref, fresh);
  return { ...copy, 'bom-ref': fresh };
}

/**
 * A tool an earlier input already named, under this input's own ref: the duplicate is not
 * copied, so this input's evidence must cite the kept entry. The kept entry takes the ref when
 * it has none and the ref is free; otherwise the ref is recorded as renamed to the kept one.
 */
function mapDuplicateRef(duplicate: any, kept: any, usedRefs: Set<string>, renamed: Map<string, string>): void {
  const ref = duplicate['bom-ref'];
  if (typeof ref !== 'string' || !ref || ref === kept['bom-ref']) return;
  if (typeof kept['bom-ref'] !== 'string' || !kept['bom-ref']) {
    if (!usedRefs.has(ref)) { kept['bom-ref'] = ref; usedRefs.add(ref); return; }
    let fresh = ref;
    for (let n = 2; usedRefs.has(fresh); n++) fresh = `${ref}-${n}`;
    kept['bom-ref'] = fresh;
    usedRefs.add(fresh);
  }
  renamed.set(ref, kept['bom-ref']);
}

/** Calls `visit` on every component object in a BOM: components, nested and in formulation. */
function forEachComponent(bom: any, visit: (c: any) => void): void {
  const walk = (list: unknown) => {
    if (!Array.isArray(list)) return;
    for (const c of list) {
      if (!c || typeof c !== 'object') continue;
      visit(c);
      walk(c.components);
    }
  };
  walk(bom?.components);
  if (bom?.metadata?.component) { visit(bom.metadata.component); walk(bom.metadata.component.components); }
  if (Array.isArray(bom?.formulation)) for (const f of bom.formulation) walk(f?.components);
}

/** The tool refs a component's evidence names, whether identity is an array (1.6) or one object (1.5). */
function evidenceIdentities(component: any): any[] {
  const identity = component?.evidence?.identity;
  if (Array.isArray(identity)) return identity.filter((i: any) => i && typeof i === 'object');
  return identity && typeof identity === 'object' ? [identity] : [];
}

/** bom-refs of the input's components whose evidence names tool ref `toolRef`. */
function componentsCiting(input: any, toolRef: string): Set<string> {
  const refs = new Set<string>();
  forEachComponent(input, (c) => {
    if (typeof c['bom-ref'] !== 'string') return;
    if (evidenceIdentities(c).some((i) => Array.isArray(i.tools) && i.tools.includes(toolRef))) refs.add(c['bom-ref']);
  });
  return refs;
}

/**
 * Points the evidence of the merged components that came from `inputs[index]` at the tool's new
 * ref. A component is only rewritten when no other input has a component under the same bom-ref
 * citing the old ref: such a component was merged from both, and either attribution would be a
 * guess, so it keeps the original one.
 */
function repointEvidence(merged: any, inputs: any[], index: number, renamed: Map<string, string>): void {
  for (const [oldRef, newRef] of renamed) {
    const ours = componentsCiting(inputs[index], oldRef);
    inputs.forEach((other, i) => {
      if (i === index) return;
      for (const ref of componentsCiting(other, oldRef)) ours.delete(ref);
    });
    if (!ours.size) continue;
    forEachComponent(merged, (c) => {
      if (!ours.has(c['bom-ref'])) return;
      for (const identity of evidenceIdentities(c)) {
        if (Array.isArray(identity.tools)) identity.tools = identity.tools.map((t: unknown) => (t === oldRef ? newRef : t));
      }
    });
  }
}

export function mergeToolsFromInputs(merged: any, inputs: any[]): any {
  if (!merged) return merged;
  if (!merged.metadata) merged.metadata = {};
  const usedRefs = bomRefsIn(merged, new Set<string>());
  const current = toolsOf(merged);
  // The entry kept for each tool, so a later input naming the same tool under another ref can
  // be pointed at it.
  const keptComponents = new Map<string, any>(current.components.map((c) => [toolKey(c), c]));
  const keptServices = new Map<string, any>(current.services.map((s) => [toolKey(s), s]));
  const components = [...current.components];
  const services = [...current.services];
  const inputList = inputs || [];
  inputList.forEach((input, index) => {
    const renamed = new Map<string, string>();
    const t = toolsOf(input);
    for (const c of t.components) {
      if (isRearmTool(c)) continue;
      const key = toolKey(c);
      const kept = keptComponents.get(key);
      if (kept) { mapDuplicateRef(c, kept, usedRefs, renamed); continue; }
      const copy = copyTool(c, usedRefs, renamed);
      // `type` is required on a 1.5+ component; a producer that left it out still named a tool.
      if (!copy.type) copy.type = 'application';
      keptComponents.set(key, copy);
      components.push(copy);
    }
    for (const s of t.services) {
      if (isRearmTool(s)) continue;
      const key = toolKey(s);
      const kept = keptServices.get(key);
      if (kept) { mapDuplicateRef(s, kept, usedRefs, renamed); continue; }
      const copy = copyTool(s, usedRefs, renamed);
      keptServices.set(key, copy);
      services.push(copy);
    }
    repointEvidence(merged, inputList, index, renamed);
  });
  if (isLegacySpec(merged.specVersion)) {
    // 1.4 has no tool services; the legacy array holds tool entries only.
    merged.metadata.tools = components.map(componentAsLegacyTool);
  } else {
    merged.metadata.tools = services.length ? { components, services } : { components };
  }
  return merged;
}

/**
 * Carries the inputs' `metadata.lifecycles` (CycloneDX 1.5+) into the merged BOM, de-duplicated
 * by phase or by name. `rearm bomutils merge-boms` writes fresh metadata, so a merge of two
 * build-time SBOMs said nothing about when its data was produced. Mutates and returns `merged`.
 */
export function mergeLifecyclesFromInputs(merged: any, inputs: any[]): any {
  if (!merged) return merged;
  if (!merged.metadata) merged.metadata = {};
  const key = (l: any) => (l && typeof l.phase === 'string' ? `phase:${l.phase}` : (l && typeof l.name === 'string' ? `name:${l.name}` : ''));
  const lifecycles: any[] = Array.isArray(merged.metadata.lifecycles) ? [...merged.metadata.lifecycles] : [];
  const seen = new Set(lifecycles.map(key));
  for (const input of inputs || []) {
    const ls = input?.metadata?.lifecycles;
    if (!Array.isArray(ls)) continue;
    for (const l of ls) {
      const k = key(l);
      if (!k || seen.has(k)) continue;
      seen.add(k);
      lifecycles.push(l);
    }
  }
  if (lifecycles.length && !isLegacySpec(merged.specVersion)) merged.metadata.lifecycles = lifecycles;
  return merged;
}
