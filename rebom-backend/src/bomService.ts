/**
 * BOM Service - Re-export Layer
 * 
 * This file maintains backward compatibility by re-exporting all functions
 * from the focused service modules. All actual implementations are now in
 * the services/bom/ directory.
 * 
 * Service modules:
 * - bomCrudService: Read operations
 * - bomSearchService: Search operations
 * - bomExportService: Excel/CSV export
 * - bomProcessingService: BOM transformations
 * - bomMergeService: Merge operations
 * - bomAddService: Add/store operations
 * - bomMapper: Data Object Pattern
 */

// CRUD Operations
export {
  findAllBoms,
  findBomObjectById,
  findBomMetasBySerialNumber,
  findBomBySerialNumberAndVersion,
  findBomsByIds,
  findBomByOrgAndDigest,
  findRawBomObjectById
} from './services/bom/bomCrudService';

// Search Operations
export {
  bomMetadataById,
  findBomByMeta,
  updateSearchObj
} from './services/bom/bomSearchService';

// Export Operations
export {
  bomToExcel,
  bomToCsv
} from './services/bom/bomExportService';

// Processing Operations
export {
  augmentBomWithComponentContext,
  augmentBomForStorage,
  overrideRootComponent, // @deprecated - use augmentBomWithComponentContext
  extractTldFromBom,
  extractDevFilteredBom,
  establishPurl,
  computeRootDepIndex,
  createRebomToolObject,
  attachRebomToolToBom,
  computeBomDigest
} from './services/bom/bomProcessingService';

// Merge Operations
export {
  mergeBoms,
  mergeAndStoreBoms,
  mergeBomObjects
} from './services/bom/bomMergeService';

// Add Operations
export {
  addBom
} from './services/bom/bomAddService';

// Data Mapper
export { BomMapper } from './services/bom/bomMapper';
