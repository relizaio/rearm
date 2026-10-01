/** Mirrors the GraphQL LatestFixVerdict enum: whether a component's latest version fixes a finding. */
export enum LatestFixVerdict {
    FIXES = 'FIXES',
    DOES_NOT_FIX = 'DOES_NOT_FIX',
    NOT_ABOVE_CURRENT = 'NOT_ABOVE_CURRENT',
    NOT_IN_ADVISORY_RANGE = 'NOT_IN_ADVISORY_RANGE',
    NO_RANGE_DATA = 'NO_RANGE_DATA',
    UNCOMPARABLE = 'UNCOMPARABLE'
}
