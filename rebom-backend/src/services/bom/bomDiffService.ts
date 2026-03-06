import { mergeBomObjects, findBomObjectById, augmentBomWithComponentContext } from '../../bomService';
import { logger } from '../../logger';
import { RebomOptions, BomDiffResult, ComponentDiff } from '../../types';
import { createTempFile, deleteTempFile, shellExec } from '../../utils';
async function mergeBomsForDiff(ids:[string], org: string){

    const rebomOptions: RebomOptions = {
      structure: "", group: "rearmrebom", name: "diff-temp", version: "1",
      serialNumber: "",
      belongsTo: "",
      notes: "",
      tldOnly: false,
      bomState: "",
      mod: "",
      storage: "",
      stripBom: "",
      bomVersion: ""
  }

    const bomRecords =  await Promise.all(ids.map(async(id, index : number) => {
      
        const bomRecord = await findBomObjectById(id, org)
        // Augment the root component with a custom purl and version specific to diff functionality
        // This prevents the root component from showing up in diff results
        const augmentedBom = augmentBomWithComponentContext(bomRecord, {...rebomOptions, version: "1"+ index, purl: "pkg:generic/diff/test@"+ index}, new Date())
        
        
        return augmentedBom
    }))

    return await mergeBomObjects(bomRecords, rebomOptions)

  }

  export async function bomDiff(fromIds:[string], toIds: [string], org: string): Promise<ComponentDiff> {
    const result: ComponentDiff = {
        added: [],
        removed: []
    };

    const bomDiffResult: BomDiffResult = await bomDiffExec(fromIds, toIds, org)

    // First pass: collect all components
    for (const group of Object.values(bomDiffResult.componentVersions)) {
        group.added.forEach(c => {
            result.added.push({ purl: c.purl, version: c.version });
        });
        group.removed.forEach(c => {
            result.removed.push({ purl: c.purl, version: c.version });
        });
    }
    
    // Deduplicate: Remove components that appear in both added and removed with the same version
    // These are unchanged components that cyclonedx-cli incorrectly reports in multiple groups
    const addedMap = new Map(result.added.map(c => [`${c.purl}@${c.version}`, c]));
    const removedMap = new Map(result.removed.map(c => [`${c.purl}@${c.version}`, c]));
    const duplicateKeys = new Set<string>();
    
    for (const key of addedMap.keys()) {
        if (removedMap.has(key)) {
            duplicateKeys.add(key);
        }
    }
    
    // Remove duplicates from both lists
    if (duplicateKeys.size > 0) {
        result.added = result.added.filter(c => !duplicateKeys.has(`${c.purl}@${c.version}`));
        result.removed = result.removed.filter(c => !duplicateKeys.has(`${c.purl}@${c.version}`));
    }
    
    // Check for remaining duplicates (for debugging)
    const finalAddedMap = new Map(result.added.map(c => [c.purl, c]));
    const finalRemovedMap = new Map(result.removed.map(c => [c.purl, c]));
    const duplicates: Array<{purl: string, addedVersion: string, removedVersion: string}> = [];
    
    for (const [purl, addedComp] of finalAddedMap.entries()) {
        const removedComp = finalRemovedMap.get(purl);
        if (removedComp && addedComp.version === removedComp.version) {
            duplicates.push({
                purl,
                addedVersion: addedComp.version,
                removedVersion: removedComp.version
            });
        }
    }
    
    if (duplicates.length > 0) {
        // Find ALL diff groups that contain the first duplicate component
        const firstDuplicatePurl = duplicates[0].purl;
        const allGroupsForFirstDup = Object.entries(bomDiffResult.componentVersions)
            .filter(([_, group]: [string, any]) => {
                const hasInAdded = group.added.some((c: any) => c.purl === firstDuplicatePurl);
                const hasInRemoved = group.removed.some((c: any) => c.purl === firstDuplicatePurl);
                return hasInAdded || hasInRemoved;
            })
            .map(([purl, group]: [string, any]) => ({
                groupPurl: purl,
                addedCount: group.added.length,
                removedCount: group.removed.length,
                unchangedCount: group.unchanged.length,
                hasFirstDupInAdded: group.added.some((c: any) => c.purl === firstDuplicatePurl),
                hasFirstDupInRemoved: group.removed.some((c: any) => c.purl === firstDuplicatePurl),
                addedComponents: group.added.map((c: any) => ({ purl: c.purl, version: c.version })),
                removedComponents: group.removed.map((c: any) => ({ purl: c.purl, version: c.version }))
            }));
        
        const duplicateGroups = allGroupsForFirstDup;
        
        logger.warn({ 
            fromIds, 
            toIds, 
            duplicateCount: duplicates.length,
            duplicates: duplicates.slice(0, 10),
            firstDuplicatePurl,
            groupsContainingFirstDup: duplicateGroups.length,
            duplicateGroups
        }, 'BOM_DIFF: DUPLICATE COMPONENTS DETECTED - same purl@version in both added and removed!');
    }
    
    if (result.added.length === result.removed.length && result.added.length > 0) {
        logger.warn({ 
            fromIds, 
            toIds, 
            count: result.added.length 
        }, 'BOM_DIFF: SYMMETRIC DIFF DETECTED - same number of components in added and removed!');
    }
    
    return result

  }

  export async function bomDiffExec(fromIds:[string], toIds: [string], org: string): Promise<BomDiffResult> {
    let fromBomPath: string = ''
    let toBomPath: string = ''
    try {
      const fromBomObj = await mergeBomsForDiff(fromIds, org)
      const toBomObj = await mergeBomsForDiff(toIds, org)
    
      fromBomPath = await createTempFile(fromBomObj)
      toBomPath = await createTempFile(toBomObj)
      const command = ['diff']
      // logger.info(`fromBomPath: ${fromBomPath}`)
      // logger.info(`toBomPath: ${toBomPath}`)
      command.push(
        '--from-format', 'json',
        '--to-format', 'json',
        '--output-format', 'json',
        '--component-versions',
        fromBomPath,
        toBomPath
      )

      const diffResultString: string = await shellExec('cyclonedx-cli',command)
      const diffResult: BomDiffResult = JSON.parse(diffResultString)
      
      // Clean up temporary files after successful diff
      await deleteTempFile(fromBomPath)
      await deleteTempFile(toBomPath)
      
      return diffResult
    } catch (e) {
      logger.error({ err: e }, "Error During diff")
      // Clean up temporary files in case of error
      if (fromBomPath) {
        await deleteTempFile(fromBomPath)
      }
      if (toBomPath) {
        await deleteTempFile(toBomPath)
      }
      throw e
    }

  }