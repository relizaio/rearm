/**
 * Shared utilities for finding/vulnerability display components.
 * Extracted from FindingChangesDisplay.vue and FindingChangesDisplayWithAttribution.vue.
 */

import Swal from 'sweetalert2'
import { FindingType } from '@/constants/findingType'

/** The severities a finding can carry, worst first. */
export const ROW_SEVERITIES = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'UNASSIGNED'] as const
export type RowSeverity = typeof ROW_SEVERITIES[number]

// '-' is the placeholder violations carry in place of a severity.
export const SEVERITY_ORDER: string[] = [...ROW_SEVERITIES, '-']

/**
 * The severity bucket of a finding, as the findings table's Severity filter reads
 * it: a missing, '-' or unrecognised severity is UNASSIGNED.
 */
export function severityBucketOf (finding: { severity?: string }): RowSeverity {
    const s = finding.severity || ''
    return (ROW_SEVERITIES as readonly string[]).includes(s) ? s as RowSeverity : 'UNASSIGNED'
}

export function emptySeverityCounts (): Record<RowSeverity, number> {
    return Object.fromEntries(ROW_SEVERITIES.map(s => [s, 0])) as Record<RowSeverity, number>
}

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

/** osv.dev indexes every id family ReARM sees (CVE, GHSA, PYSEC, RUSTSEC, GO, DEBIAN-CVE, ALPINE-CVE, ...). */
export function osvUrlFor(vulnId: string): string {
    return `https://osv.dev/vulnerability/${encodeURIComponent(vulnId)}`
}

export function nvdUrlFor(cveId: string): string {
    return `https://nvd.nist.gov/vuln/detail/${encodeURIComponent(cveId)}`
}

export function githubAdvisoryUrlFor(ghsaId: string): string {
    return `https://github.com/advisories/${encodeURIComponent(ghsaId)}`
}

/** MITRE page for a CWE id ("CWE-79", "CWE-0079"); null when the id carries no number. */
export function cweUrlFor(cweId: string): string | null {
    if (!cweId || !cweId.startsWith('CWE-')) return null
    const num = parseInt(cweId.slice(4), 10)
    return Number.isNaN(num) ? null : `https://cwe.mitre.org/data/definitions/${num}.html`
}

/**
 * Normalizes the finding-type spellings the UI receives: the findings table rows
 * ('Vulnerability'), analysis records ('VULNERABILITY') and the changelog ('VULN').
 */
export function findingTypeOf(type: string | null | undefined): FindingType | null {
    switch (type) {
        case 'Vulnerability':
        case 'VULNERABILITY':
        case 'VULN':
            return FindingType.VULNERABILITY
        case 'Weakness':
        case 'WEAKNESS':
            return FindingType.WEAKNESS
        case 'Violation':
        case 'VIOLATION':
            return FindingType.VIOLATION
        default:
            return null
    }
}

/**
 * Type of an id typed into the release-by-finding search, which offers
 * vulnerability ids and CWE ids. Null for a blank search.
 */
export function findingTypeOfSearchedId(id: string | null | undefined): FindingType | null {
    const trimmed = (id || '').trim()
    if (!trimmed) return null
    return /^CWE-/i.test(trimmed) ? FindingType.WEAKNESS : FindingType.VULNERABILITY
}

/**
 * Where a finding id leads. A vulnerability opens the in-app details panel; its
 * href stays the osv.dev page, which copy-link and middle-click still reach. A
 * weakness links out to MITRE. Violations and unknown types are plain text.
 */
export type FindingIdLink =
    | { action: 'details', href: string }
    | { action: 'external', href: string }
    | { action: 'none' }

export function findingIdLink(id: string, type: FindingType | null): FindingIdLink {
    if (!id) return { action: 'none' }
    if (type === FindingType.VULNERABILITY) return { action: 'details', href: osvUrlFor(id) }
    if (type === FindingType.WEAKNESS) {
        const href = cweUrlFor(id)
        return href ? { action: 'external', href } : { action: 'none' }
    }
    return { action: 'none' }
}

/**
 * Follows a finding id link on click: the details panel through onVulnClick
 * when the host has one, else the external page through the consent dialog.
 */
export function followFindingIdLink(e: Event, id: string, link: FindingIdLink,
    onVulnClick?: (vulnId: string) => void): void {
    if (link.action === 'none') return
    e.preventDefault()
    if (link.action === 'details' && onVulnClick) {
        onVulnClick(id)
        return
    }
    void openExternalLink(link.href)
}

/** Render-function form of a finding id for h()-built data-table cells. */
export function renderFindingId(h: any, id: string, type: FindingType | null,
    onVulnClick?: (vulnId: string) => void): any {
    const link = findingIdLink(id, type)
    if (link.action === 'none') return id
    return h('a', {
        href: link.href,
        target: '_blank',
        rel: 'noopener noreferrer',
        title: link.action === 'details' && onVulnClick ? 'Show vulnerability details' : undefined,
        onClick: (e: Event) => followFindingIdLink(e, id, link, onVulnClick)
    }, id)
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
