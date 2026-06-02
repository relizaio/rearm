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
    parseBomById(id: ID!, org: ID!): ParsedBom
    bomDiff(fromIds: [ID], toIds: [ID], org: ID!): BomDiffResult
    parseSarifContent(sarifContent: String!): [Weakness]
    parseCycloneDxContent(vdrContent: String!): [Vulnerability]
    health: HealthStatus!
    getBearIntegration(org: ID!): BearIntegration
    getBomDigestProbe(bomContent: BomContentInput!): String!
    getEnrichedBomProbe(bomContent: BomContentInput!): EnrichedBomProbeResult!
  }

  type Mutation {
    mergeAndStoreBomsExcel(ids: [ID]!, rebomOptions: RebomOptions!, org: ID): String
    addBom(bomInput: BomInput!): Bom
    mergeAndStoreBoms(ids: [ID]!, rebomOptions: RebomOptions!, org: ID): Bom
    mergeAndStoreBomsCsv(ids: [ID]!, rebomOptions: RebomOptions!, org: ID): String
    triggerEnrichment(id: ID!, org: ID!, force: Boolean): EnrichmentTriggerResult
    setBearIntegration(org: ID!, uri: String!, apiKey: String!, skipPatterns: [String]): BearIntegration
    updateBearSkipPatterns(org: ID!, skipPatterns: [String]): BearIntegration
    deleteBearIntegration(org: ID!): Boolean!
    """
    Verify a detached signature against a payload using one of the
    supported wire formats (SSH | GPG). The trust store is the
    aggregated list of enrolled public keys for the org — passed
    inline by rearm-backend so this resolver is stateless w.r.t.
    the ReARM keystore. Never consults the public key embedded in
    the signature blob; the trust store is authoritative.
    """
    verifySignature(input: VerifySignatureInput!): VerifySignatureResult!
  }

  input VerifySignatureInput {
    format: VerifierSignatureFormat!
    """Base64-encoded raw signature bytes (the gpgsig blob or the ssh-keygen -Y signature)."""
    signatureB64: String!
    """Base64-encoded signed payload bytes (commit object body, etc.)."""
    payloadB64: String!
    """Base64-encoded trust store — ssh allowed_signers file for SSH; concatenated ASCII-armoured pubkeys for GPG."""
    trustStoreB64: String!
    """SSH allowed_signers principal expected on the signature; optional."""
    expectedIdentity: String
  }

  type VerifySignatureResult {
    verdict: VerifierVerdict!
    """Fingerprint of the enrolled key that matched, when verdict = VERIFIED."""
    matchedFingerprint: String
    """Free-form diagnostic for the UI / debugging."""
    details: String
  }

  enum VerifierSignatureFormat {
    SSH
    GPG
  }

  enum VerifierVerdict {
    VERIFIED
    INVALID_SIGNATURE
    UNKNOWN_KEY
    ERRORED
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

  type BomComponent {
    canonicalPurl: String!
    fullPurl: String!
    type: String
    group: String
    name: String
    version: String
    isRoot: Boolean!
    cpe: String
    licenses: [String!]!
  }

  type BomDependency {
    sourceCanonicalPurl: String!
    sourceFullPurl: String!
    targetCanonicalPurl: String!
    targetFullPurl: String!
    relationshipType: String!
  }

  type ParsedBom {
    components: [BomComponent]!
    dependencies: [BomDependency]!
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
  input BomContentInput {
    format: BomFormat!
    bom: Object!
    org: ID
  }

  type EnrichedBomProbeResult {
    status: EnrichmentStatus!
    enrichedBom: String
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

  type BearIntegration {
    uri: String
    configured: Boolean!
    skipPatterns: [String]
  }

  scalar Object
  scalar DateTime
`;

export default typeDefs;