/**
 * Shared utilities for finding/vulnerability display components.
 * Extracted from FindingChangesDisplay.vue and FindingChangesDisplayWithAttribution.vue.
 */

import Swal from 'sweetalert2'

export const SEVERITY_ORDER = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'UNASSIGNED', '-']

export function getSeverityIndex(severity?: string): number {
    if (!severity) return SEVERITY_ORDER.length
    const index = SEVERITY_ORDER.indexOf(severity)
    return index === -1 ? SEVERITY_ORDER.length : index
}

export function getSeverityTagType(severity?: string): 'default' | 'error' | 'warning' | 'info' | 'success' | 'primary' {
    switch (severity) {
        case 'CRITICAL':
        case 'HIGH':
            return 'error'
        case 'MEDIUM':
            return 'warning'
        case 'LOW':
            return 'info'
        case 'UNASSIGNED':
            return 'default'
        default:
            return 'default'
    }
}

export function getFindingTypeTagType(type: string): 'default' | 'error' | 'warning' | 'info' | 'success' | 'primary' {
    switch (type) {
        case 'VULN':
            return 'error'
        case 'VIOLATION':
            return 'warning'
        case 'WEAKNESS':
            return 'info'
        default:
            return 'default'
    }
}

export function getFindingUrl(id: string): string | null {
    if (!id) return null
    if (id.startsWith('ALPINE-CVE-') || id.startsWith('CVE-') || id.startsWith('GHSA-')) {
        return `https://osv.dev/vulnerability/${id}`
    }
    if (id.startsWith('CWE-')) {
        const raw = id.slice(4)
        const num = String(parseInt(raw, 10))
        if (num && num !== 'NaN') {
            return `https://cwe.mitre.org/data/definitions/${num}.html`
        }
    }
    return null
}

const LS_KEY = 'rearm_external_link_consent_until'

/**
 * Shared normalizers turning the raw ReleaseVulnerabilityInfo / ReleaseViolationInfo /
 * ReleaseWeaknessInfo GraphQL records into the flat shape FindingListSection renders.
 * Extracted from FindingChangesDisplay.vue so multiple changelog surfaces
 * (per-release finding changes + over-time finding changes) share one source of truth.
 */
export interface NormalizedReleaseFinding {
    findingId: string
    affectedComponent: string
    severity?: string
    aliases: string[]
    type: 'VULN' | 'VIOLATION' | 'WEAKNESS'
    typeLabel: string
    analysisState: string | null
    knownExploited?: boolean
}

export function normalizeReleaseVuln(v: any): NormalizedReleaseFinding {
    return {
        findingId: v.vulnId || '',
        affectedComponent: v.purl || '',
        severity: v.severity || '',
        aliases: Array.isArray(v.aliases) ? v.aliases.map((a: any) => typeof a === 'string' ? a : a.aliasId) : [],
        type: 'VULN',
        typeLabel: 'VULNERABILITY',
        analysisState: v.analysisState || null,
        knownExploited: !!v.knownExploited
    }
}

export function normalizeReleaseViolation(v: any): NormalizedReleaseFinding {
    return {
        findingId: v.type || '',
        affectedComponent: v.purl || '',
        severity: undefined,
        aliases: [],
        type: 'VIOLATION',
        typeLabel: 'VIOLATION',
        analysisState: v.analysisState || null
    }
}

export function normalizeReleaseWeakness(w: any): NormalizedReleaseFinding {
    return {
        findingId: w.cweId || w.ruleId || '',
        affectedComponent: w.location || '',
        severity: w.severity || '',
        aliases: [],
        type: 'WEAKNESS',
        typeLabel: 'WEAKNESS',
        analysisState: w.analysisState || null
    }
}

/**
 * Normalize whichever of vulnerability / violation / weakness is non-null on a
 * MetricsRevisionFindingChange record (exactly one is set per the backend contract).
 */
export function normalizeFindingChangeRecord(rec: {
    vulnerability?: any
    violation?: any
    weakness?: any
}): NormalizedReleaseFinding | null {
    if (rec.vulnerability) return normalizeReleaseVuln(rec.vulnerability)
    if (rec.violation) return normalizeReleaseViolation(rec.violation)
    if (rec.weakness) return normalizeReleaseWeakness(rec.weakness)
    return null
}

/**
 * Type-scoped id key identifying a single logical finding (e.g. a CVE) across
 * releases/components, used for CLIENT-SIDE grouping of the over-time timeline.
 * Deliberately excludes the per-release purl/location so a "same CVE in two
 * releases" case collapses to one group. NOTE: this is a UI grouping key, NOT
 * the backend `findingKey` (`vulnId|purl`) used by the findingAttributionByDate /
 * findingChangeTimelineByDate drill-down -- those pass the DTO's `findingKey`.
 */
export function findingChangeRecordKey(rec: {
    vulnerability?: any
    violation?: any
    weakness?: any
}): string | null {
    if (rec.vulnerability) return `VULN-${rec.vulnerability.vulnId}`
    if (rec.violation) return `VIOLATION-${rec.violation.type}`
    if (rec.weakness) return `WEAKNESS-${rec.weakness.cweId || rec.weakness.ruleId || ''}`
    return null
}

export function sortBySeverityThenId(findings: NormalizedReleaseFinding[]): NormalizedReleaseFinding[] {
    return [...findings].sort((a, b) => {
        const severityDiff = getSeverityIndex(a.severity) - getSeverityIndex(b.severity)
        if (severityDiff !== 0) return severityDiff
        return String(a.findingId || '').localeCompare(String(b.findingId || ''))
    })
}

export async function openExternalLink(href: string): Promise<void> {
    try {
        const now = Date.now()
        const stored = localStorage.getItem(LS_KEY)
        if (stored && Number(stored) > now) {
            window.open(href, '_blank')
            return
        }
        const result = await Swal.fire({
            icon: 'info',
            title: 'Open external link?\n',
            text: 'This will open a vulnerability database resource external to ReARM. Please confirm that you want to proceed.',
            showCancelButton: true,
            confirmButtonText: 'Open',
            cancelButtonText: 'Cancel',
            input: 'checkbox',
            inputValue: 0,
            inputPlaceholder: "Don't ask me again for 15 days"
        })
        if (result.isConfirmed) {
            if (result.value === 1) {
                const fifteenDaysMs = 15 * 24 * 60 * 60 * 1000
                localStorage.setItem(LS_KEY, String(now + fifteenDaysMs))
            }
            window.open(href, '_blank')
        }
    } catch (err) {
        window.open(href, '_blank')
    }
}
