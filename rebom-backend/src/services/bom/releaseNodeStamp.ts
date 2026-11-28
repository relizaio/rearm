// rebom's stamp on the node it builds for a release, and the rule that only rebom's own stamp is
// ever served (SCORE-60 ADR-9). A leaf module with no imports, so the OCI layer can use it without
// an import cycle (bomProcessingService imports ../oci).

/**
 * rebom writes this property, value `true`, on every merged root it makes (SCORE-58 ADR-5, built as
 * SCORE-60 ADR-1). rearm-saas reads it to mark the organization's own entries
 * (`reliza:support:selfComponent`) for a node it holds no row for, and the include internal
 * metadata option removes it.
 */
export const RELEASE_NODE_PROPERTY = 'reliza:export:releaseNode'

/**
 * rebom writes this property on the root of a merge whose linkOrphanComponents made top-level
 * components that no dependency entry named direct dependencies of that root: their number, as a
 * decimal string (FDAW-160 3.1). It travels with the node into a product merge. Like
 * RELEASE_NODE_PROPERTY it is only ever rebom's own record: every uploaded copy is removed.
 */
export const DEPENDENCIES_INFERRED_PROPERTY = 'reliza:export:dependenciesInferred'

/** The `meta.mod` rebom writes on a row it stored from its own merge. */
export const REBOM_MERGE_MOD = 'rebom'

/** The `meta.mod` rebom writes on every row it did not merge (every upload). */
export const UPLOAD_MOD = 'raw'

/** True for a `properties` entry named RELEASE_NODE_PROPERTY, whatever its value. */
export function isReleaseNodeStamp(property: any): boolean {
  return !!property && typeof property === 'object' && property.name === RELEASE_NODE_PROPERTY
}

/**
 * True for a `properties` entry that is one of rebom's node stamps (RELEASE_NODE_PROPERTY or
 * DEPENDENCIES_INFERRED_PROPERTY), whatever its value.
 */
export function isRebomNodeStamp(property: any): boolean {
  return !!property && typeof property === 'object'
    && (property.name === RELEASE_NODE_PROPERTY || property.name === DEPENDENCIES_INFERRED_PROPERTY)
}

/** True only for a row rebom stored from its own merge: `meta.mod` exactly `rebom`. */
export function isRebomMergeOutput(meta: any): boolean {
  return meta?.mod === REBOM_MERGE_MOD
}

export type ReleaseNodeStripResult = {
  bom: any,
  count: number
}

/**
 * Removes every rebom node stamp (isRebomNodeStamp: RELEASE_NODE_PROPERTY and
 * DEPENDENCIES_INFERRED_PROPERTY), whatever its value, from every
 * object of the document at any depth (the walk of rearm-saas's stripPropertiesEverywhere: every
 * array and every object is entered). Pure: with nothing to remove it returns the input itself;
 * otherwise a copy that shares every untouched subtree, the input and its nodes never mutated.
 */
export function stripReleaseNodeStamps(bom: any): ReleaseNodeStripResult {
  let count = 0
  const walk = (node: any): any => {
    if (Array.isArray(node)) {
      let changed = false
      const out = node.map((item) => {
        const next = walk(item)
        if (next !== item) changed = true
        return next
      })
      return changed ? out : node
    }
    if (node === null || typeof node !== 'object') return node
    let copy: any = null
    for (const key of Object.keys(node)) {
      const value = node[key]
      let next = walk(value)
      if (key === 'properties' && Array.isArray(next)) {
        const kept = next.filter((p: any) => !isRebomNodeStamp(p))
        if (kept.length !== next.length) {
          count += next.length - kept.length
          next = kept
        }
      }
      if (next !== value) {
        if (!copy) copy = { ...node }
        copy[key] = next
      }
    }
    return copy ?? node
  }
  const out = walk(bom)
  return { bom: count === 0 ? bom : out, count }
}
