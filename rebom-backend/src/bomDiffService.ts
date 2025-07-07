import { mergeBomObjects, findBomObjectById } from "./bomService"
import { logger } from './logger';
import { RebomOptions } from './types';


const utils = require('./utils')
import { BomDiffResult, ComponentDiff } from "./bomDiffResult.interface"
async function mergeBomsForDiff(ids:[string], org: string){
    const bomRecords =  await Promise.all(ids.map(async(id) => findBomObjectById(id, org)))
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
    try {
      const fromBomObj = await mergeBomsForDiff(fromIds, org)
      const toBomObj = await mergeBomsForDiff(toIds, org)
    
      const fromBomPath : string = await utils.createTempFile(fromBomObj)
      const toBomPath : string = await utils.createTempFile(toBomObj)
      const command = ['diff']
      command.push(
        '--from-format', 'json',
        '--to-format', 'json',
        '--output-format', 'json',
        '--component-versions',
        toBomPath,
        fromBomPath
      )

      const diffResultString: string = await utils.shellExec('cyclonedx-cli',command)
      const diffResult: BomDiffResult = JSON.parse(diffResultString)
      return diffResult
    } catch (e) {
      logger.error("Error During diff", e)
      throw e
    }

  }