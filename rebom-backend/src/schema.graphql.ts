import gql from 'graphql-tag';

// The GraphQL schema
const typeDefs = gql`
  type Query {
    hello: String
    dbtest: String
    allBoms: [Bom]
    findBom(bomSearch: BomSearch): [Bom]
    bomById(id: ID, org: ID): Object
    rawBomId(id: ID, org: ID): Object
    bomBySerialNumberAndVersion(serialNumber: ID!, version: Int!, org: ID!, raw: Boolean): Object
    bomMetaBySerialNumber(serialNumber: ID!, org: ID!): [BomMeta]
    bomDiff(fromIds: [ID], toIds: [ID], org: ID!): BomDiffResult
  }

  type Mutation {
    addBom(bomInput: BomInput!): Bom
    mergeAndStoreBoms(ids: [ID]!, rebomOptions: RebomOptions!, org: ID): Bom
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
  input BomInput {
    meta: String
    bom: Object
    tags: Object
    org: ID
    rebomOptions: RebomOptions
  }

  input RebomOptions {
    # rebomOverride: RebomOverride
    # mergeOptions: RebomMergeOptions
    # releaseId: ID
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
  }

  input RebomOverride {
    name: String!
    group: String!
    version: String!
    belongsTo: String
    hash: String
  }
  input RebomMergeOptions {
    belongsTo: String
    tldOnly: Boolean!
    structure: String!
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