// Presentation helpers for the element checks (elements.md §7): the report the board cut for a
// document, read under the document it is about.
//
// Pure and dependency-free like agentDocuments.ts. A CHECK_REPORT round is one of the task's
// documents; everything here works over the documents the task query already carries.

import type { DocumentRelease } from '@/utils/agentDocuments'

export interface CheckOffence {
    elementId?: string | null
    release?: string | null
    message?: string | null
}

export interface CheckResult {
    check?: string | null
    result?: string | null
    blocking?: boolean | null
    reason?: string | null
    offences?: CheckOffence[] | null
}

export interface CheckReport {
    catalogueVersion?: string | null
    grammarVersion?: string | null
    digest?: string | null
    scope?: {
        task?: string | null
        checked?: string | null
        releases?: { release?: string | null, specification?: string | null, elementsDigest?: string | null, lifecycle?: string | null }[] | null
    } | null
    results?: CheckResult[] | null
}

export interface CatalogueEntry {
    name?: string | null
    description?: string | null
    skipsWhen?: string | null
}

/** The report a CHECK_REPORT release carries, or null. */
export function reportOf (release?: DocumentRelease | null): CheckReport | null {
    const c = (release?.document as any)?.checks
    return c && typeof c === 'object' ? c as CheckReport : null
}

/**
 * The newest report about one document among a task's documents (newest first, as the server
 * returns them): the first CHECK_REPORT round whose scope names it as the checked release.
 */
export function latestReportFor (documents: DocumentRelease[] | null | undefined,
    checked?: string | null): DocumentRelease | null {
    if (!checked) return null
    return (documents ?? []).find(d => d?.document?.specification === 'CHECK_REPORT'
        && reportOf(d)?.scope?.checked === checked
        && !['PENDING', 'CANCELLED', 'REJECTED'].includes(d?.lifecycle ?? '')) ?? null
}

export interface Summary {
    pass: number
    fail: number
    skip: number
    /** Checks that failed and that the board blocks the hand-over on. */
    blockingFailed: string[]
}

export function summarise (report?: CheckReport | null): Summary {
    const out: Summary = { pass: 0, fail: 0, skip: 0, blockingFailed: [] }
    for (const r of report?.results ?? []) {
        if (r?.result === 'PASS') out.pass++
        else if (r?.result === 'SKIP') out.skip++
        else if (r?.result === 'FAIL') {
            out.fail++
            if (r.blocking && r.check) out.blockingFailed.push(r.check)
        }
    }
    return out
}

/** One line for the document row: "8 pass · 1 fail · 1 skip". */
export function summaryLine (s: Summary): string {
    return `${s.pass} pass · ${s.fail} fail · ${s.skip} skip`
}

/**
 * Whether a report describes inputs that have moved on: a release in its scope, other than the
 * document itself, whose specification the task now has a newer document of with a different
 * element index. Inputs the task does not carry (bound from elsewhere) cannot be judged here and
 * do not count.
 */
export function isStale (report: CheckReport | null | undefined, documents: DocumentRelease[] | null | undefined): boolean {
    const checked = report?.scope?.checked
    for (const s of report?.scope?.releases ?? []) {
        if (!s?.specification || s.release === checked) continue
        const now = (documents ?? []).find(d => d?.document?.specification === s.specification
            && !!(d?.document as any)?.elements
            && !['PENDING', 'CANCELLED', 'REJECTED'].includes(d?.lifecycle ?? ''))
        const digest = (now?.document as any)?.elements?.digest
        if (now && now.uuid !== s.release && digest && digest !== s.elementsDigest) return true
    }
    return false
}

/** Tag colour for a check result. */
export function resultType (result?: string | null): 'success' | 'error' | 'default' {
    if (result === 'PASS') return 'success'
    if (result === 'FAIL') return 'error'
    return 'default'
}

/** What a check means, from the catalogue; a coverage gate by its pattern. */
export function describeCheck (catalogue: CatalogueEntry[] | null | undefined, check?: string | null): string {
    if (!check) return ''
    const exact = (catalogue ?? []).find(c => c?.name === check)
    if (exact?.description) return exact.description
    if (check.startsWith('coverage.')) {
        return (catalogue ?? []).find(c => c?.name === 'coverage.<gate>')?.description ?? 'a coverage gate of this board'
    }
    return ''
}

/** Offences grouped by element, in the order they were reported. */
export function offencesByElement (result?: CheckResult | null): { elementId: string, messages: string[] }[] {
    const groups = new Map<string, string[]>()
    for (const o of result?.offences ?? []) {
        const id = o?.elementId ?? '—'
        if (!groups.has(id)) groups.set(id, [])
        groups.get(id)!.push(o?.message ?? '')
    }
    return Array.from(groups.entries()).map(([elementId, messages]) => ({ elementId, messages }))
}
