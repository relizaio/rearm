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
        toBomPath,
        fromBomPath
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