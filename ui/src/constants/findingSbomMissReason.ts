/** Mirrors the GraphQL FindingSbomMissReason enum: why no SBOM component of the release matched a finding. */
export enum FindingSbomMissReason {
    NO_PURL = 'NO_PURL',
    UNPARSEABLE_PURL = 'UNPARSEABLE_PURL',
    NO_INVENTORY = 'NO_INVENTORY',
    INVENTORY_PENDING = 'INVENTORY_PENDING',
    NOT_IN_INVENTORY = 'NOT_IN_INVENTORY'
}

