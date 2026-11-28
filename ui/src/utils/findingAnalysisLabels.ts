// The words the finding analysis screens print for the server's stored values (FDAW-150 3.1).
// Each table is built from the option array its select uses, so the picked word and the word read
// back cannot drift. The rule is fdaLabels.ts's: a known value prints its label, an unknown value
// prints humanized, null or empty prints ''. The raw name is never printed.

import { labelOrHumanized } from './fdaLabels'
import {
    ANALYSIS_JUSTIFICATION_OPTIONS,
    ANALYSIS_RESPONSE_OPTIONS,
    ANALYSIS_STATE_OPTIONS,
    FINDING_TYPE_OPTIONS,
    LOCATION_TYPE_OPTIONS
} from '@/constants/vulnAnalysis'

type Maybe = string | null | undefined

function tableOf (options: ReadonlyArray<{ label: string, value: string }>): Record<string, string> {
    const table: Record<string, string> = {}
    for (const option of options) table[option.value] = option.label
    return table
}

const FINDING_TYPE_LABELS = tableOf(FINDING_TYPE_OPTIONS)
const LOCATION_TYPE_LABELS = tableOf(LOCATION_TYPE_OPTIONS)
const ANALYSIS_STATE_LABELS = tableOf(ANALYSIS_STATE_OPTIONS)
const ANALYSIS_JUSTIFICATION_LABELS = tableOf(ANALYSIS_JUSTIFICATION_OPTIONS)
const ANALYSIS_RESPONSE_LABELS = tableOf(ANALYSIS_RESPONSE_OPTIONS)

/** AnalysisScope, as the Create dialog's Scope select words it. */
const ANALYSIS_SCOPE_LABELS: Record<string, string> = {
    ORG: 'Organization',
    RESOURCE_GROUP: 'Resource group',
    COMPONENT: 'Component',
    BRANCH: 'Branch',
    RELEASE: 'Release'
}

export const findingTypeLabel = (v: Maybe): string => labelOrHumanized(FINDING_TYPE_LABELS, v)
export const locationTypeLabel = (v: Maybe): string => labelOrHumanized(LOCATION_TYPE_LABELS, v)
export const analysisStateLabel = (v: Maybe): string => labelOrHumanized(ANALYSIS_STATE_LABELS, v)
export const analysisJustificationLabel = (v: Maybe): string => labelOrHumanized(ANALYSIS_JUSTIFICATION_LABELS, v)
export const analysisResponseLabel = (v: Maybe): string => labelOrHumanized(ANALYSIS_RESPONSE_LABELS, v)

/**
 * The scope's word. On a product's record COMPONENT reads 'Product' and BRANCH reads the
 * organisation's feature-set word as given (e.g. 'Feature Set'); with no word, 'Branch'.
 */
export function analysisScopeLabel (scope: Maybe, componentType?: Maybe, featureSetLabel?: Maybe): string {
    if (componentType === 'PRODUCT') {
        if (scope === 'COMPONENT') return 'Product'
        if (scope === 'BRANCH' && featureSetLabel) return featureSetLabel
    }
    return labelOrHumanized(ANALYSIS_SCOPE_LABELS, scope)
}

// FDAW-153 3.5: the screens' word for a finding whose state is not affected, false positive or resolved
// (isSuppressedAnalysisState). The API, the package settings and the VDR/VEX keep the word "suppressed".
export const CLOSED_FINDING_WORDS = Object.freeze({
    showClosed: 'Show closed',
    showOpen: 'Show open',
    closedHelp: 'Closed: assessed as not affected, false positive or resolved',
    openHelp: 'Open: not assessed yet, in triage, or exploitable',
    includeClosed: 'Include closed findings'
})
export type CountedThing = 'finding' | 'record'
export const closedCountText = (n: number, thing: CountedThing): string => `(${n} closed ${thing}${n === 1 ? '' : 's'})`
export const openCountText = (n: number, thing: CountedThing): string => `(${n} open ${thing}${n === 1 ? '' : 's'})`
