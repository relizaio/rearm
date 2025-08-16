import gql from 'graphql-tag';

// The GraphQL schema
const typeDefs = gql`
  type Query {
    bomByIdExcel(id: ID, org: ID): String
    rawBomIdExcel(id: ID, org: ID): String
    hello: String
    dbtest: String
    allBoms: [Bom]
    findBom(bomSearch: BomSearch): [Bom]
    bomById(id: ID, org: ID): Object
    rawBomId(id: ID, org: ID): Object
    bomByIdCsv(id: ID, org: ID): String
    rawBomIdCsv(id: ID, org: ID): String
    bomBySerialNumberAndVersion(serialNumber: ID!, version: Int!, org: ID!, raw: Boolean): Object
    bomMetaBySerialNumber(serialNumber: ID!, org: ID!): [BomMeta]
    bomDiff(fromIds: [ID], toIds: [ID], org: ID!): BomDiffResult
    parseSarifContent(sarifContent: String!): [Weakness]
  }

  type Mutation {
    mergeAndStoreBomsExcel(ids: [ID]!, rebomOptions: RebomOptions!, org: ID): String
    addBom(bomInput: BomInput!): Bom
    mergeAndStoreBoms(ids: [ID]!, rebomOptions: RebomOptions!, org: ID): Bom
    mergeAndStoreBomsCsv(ids: [ID]!, rebomOptions: RebomOptions!, org: ID): String
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
  }

  type BomMeta {
    name: String
    group: String
    bomVersion: String
    hash: String
    belongsTo: String
    tldOnly: Boolean
    structure: String
    notes: String
    stripBom: String
    serialNumber: ID
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
    tags: Object
    org: ID
    rebomOptions: RebomOptions
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
    purl: String
    rootComponentMergeMode: RootComponentMergeMode
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

  scalar Object
  scalar DateTime
`;

export default typeDefs;