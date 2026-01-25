import gql from 'graphql-tag';

// The GraphQL schema
const typeDefs = gql`
  type Query {
    bomByIdExcel(id: ID, org: ID): String
    rawBomIdExcel(id: ID, org: ID): String
    hello: String
    dbtest: String
    allBoms: [Bom]
    bomMetadataById(id: ID!, org: ID!): BomMeta
    bomById(id: ID, org: ID): Object
    rawBomId(id: ID, org: ID, format: BomFormat): Object
    bomByIdCsv(id: ID, org: ID): String
    rawBomIdCsv(id: ID, org: ID): String
    bomBySerialNumberAndVersion(serialNumber: ID!, version: Int!, org: ID!, raw: Boolean): Object
    bomMetaBySerialNumber(serialNumber: ID!, org: ID!): [BomMeta]
    bomDiff(fromIds: [ID], toIds: [ID], org: ID!): BomDiffResult
    parseSarifContent(sarifContent: String!): [Weakness]
    parseCycloneDxContent(vdrContent: String!): [Vulnerability]
    health: HealthStatus!
  }

  type Mutation {
    mergeAndStoreBomsExcel(ids: [ID]!, rebomOptions: RebomOptions!, org: ID): String
    addBom(bomInput: BomInput!): Bom
    mergeAndStoreBoms(ids: [ID]!, rebomOptions: RebomOptions!, org: ID): Bom
    mergeAndStoreBomsCsv(ids: [ID]!, rebomOptions: RebomOptions!, org: ID): String
    triggerEnrichment(id: ID!, org: ID!, force: Boolean): EnrichmentTriggerResult
  }

  type EnrichmentTriggerResult {
    triggered: Boolean!
    message: String
    bomUuid: ID
  }

  type Bom {
    uuid: ID!
    createdDate: DateTime
    lastUpdatedDate: DateTime
    meta: Object
    bom: Object
    tags: Object
    organization: ID
    public: Boolean
    bomVersion: String
    group: String
    name: String
    version: String
    duplicate: Boolean
    sourceFormat: BomFormat
    sourceSpdxUuid: ID
  }

  type BomMeta {
    uuid: ID
    name: String
    group: String
    bomVersion: String
    version: String
    bomDigest: String
    belongsTo: String
    tldOnly: Boolean
    structure: String
    notes: String
    stripBom: String
    serialNumber: ID
    createdDate: DateTime
    lastUpdatedDate: DateTime
    ignoreDev: Boolean
    enrichmentStatus: EnrichmentStatus
    enrichmentTimestamp: String
    enrichmentError: String
  }

  enum EnrichmentStatus {
    PENDING
    COMPLETED
    FAILED
    SKIPPED
  }

  type BomDiffResult {
    added: [BomDiffComponent]
    removed: [BomDiffComponent]
  }
  type BomDiffComponent {
    purl: String
    version: String
  }

  type Weakness {
    cweId: String
    ruleId: String!
    location: String
    fingerprint: String
    severity: VulnerabilitySeverity
  }

  type Vulnerability {
    purl: String!
    vulnId: String!
    severity: VulnerabilitySeverity!
  }

  type HealthStatus {
    status: String!
    database: Boolean!
    oci: Boolean!
    version: String!
  }

  enum VulnerabilitySeverity {
    CRITICAL
    HIGH
    MEDIUM
    LOW
    UNKNOWN
  }
  input BomInput {
    meta: String
    bom: Object
    format: BomFormat
    tags: Object
    org: ID
    rebomOptions: RebomOptions
    existingSerialNumber: ID  # For SPDX updates: existing serialNumber to maintain continuity
  }

  enum RootComponentMergeMode {
    PRESERVE_UNDER_NEW_ROOT
    FLATTEN_UNDER_NEW_ROOT
}

input RebomOptions {
    name: String
    group: String
    version: String
    hash: String
    belongsTo: String
    tldOnly: Boolean
    structure: String
    notes: String
    stripBom: String
    serialNumber: ID
    bomDigest: String
    originalFileDigest: String  # SHA256 of original file (for SPDX)
    originalFileSize: Int       # Size of original file in bytes (for SPDX)
    originalMediaType: String   # Media type of original file (for SPDX)
    purl: String
    rootComponentMergeMode: RootComponentMergeMode
    ignoreDev: Boolean
    bomVersion: String  # Rearm-managed version for SPDX (1, 2, 3...)
  }

  input BomSearch {
    serialNumber: ID
    version: String
    componentVersion: String
    componentGroup: String
    componentName: String,
    singleQuery: String,
    page: Int,
    offset: Int
  }

  input BomMerge {
    ids: ID,
    version: String,
    name: String,
    group: String
  }

  enum BomFormat {
    CYCLONEDX
    SPDX
  }

  scalar Object
  scalar DateTime
`;

export default typeDefs;