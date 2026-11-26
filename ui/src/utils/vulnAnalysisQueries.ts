/**
 * The selection of a finding analysis record that the Finding Analysis page and the Finding
 * Analysis Records dialog read, for the record and each history entry. Both views hand a record
 * to the Update dialog, which fills its form from it and sends responses, recommendation and
 * workaround on every save: they are selected here so a save does not clear them.
 *
 * The Pro variant adds the typed assessment fields, which the CE schema does not have yet
 * (CE catch-up deferred); a CE backend refuses a document that selects them.
 */
export function vulnAnalysisFieldsFragment (pro: boolean): string {
    const assessment = pro
        ? `
        discoveryMethod
        riskLevel
        controls {
            type
            description
        }`
        : ''
    return `
    uuid
    org
    location
    locationType
    findingId
    findingAliases
    findingType
    scope
    scopeUuid
    analysisState
    analysisJustification
    severity
    responses
    recommendation
    workaround${assessment}
    analysisHistory {
        state
        justification
        severity
        details
        createdDate
        responses
        recommendation
        workaround${assessment}
    }
    releaseDetails {
        uuid
        version
    }
    branchDetails {
        uuid
        name
    }
    componentDetails {
        uuid
        name
        type
    }
`
}
