// Presentation helpers for a document's elements (gaps §2.A, task 7e55b4a5; elements.md §8): the
// element list under a document, the links in and out of an element, the findings about it, and
// which rounds changed it.
//
// Pure and dependency-free like agentDocuments.ts. The server serves each document's element index
// on document.elements; everything here is worked out from the documents the task query already
// carries, so opening an element costs a request only for what the server alone knows -- dependents
// across the board, and the element's history.

import type { DocumentRelease, Finding } from '@/utils/agentDocuments'
import { documentFileUrl, latestRound, openFindingsOf, sortFindings } from '@/utils/agentDocuments'

export interface ElementLink {
    verb?: string | null
    target?: string | null
}

export interface Element {
    id?: string | null
    family?: string | null
    title?: string | null
    parent?: string | null
    level?: number | null
    traces?: ElementLink[] | null
    assumes?: string[] | null
    speculative?: string[] | null
    contentDigest?: string | null
    line?: number | null
}

/** An element with the document release it came from. */
export interface TaskElement extends Element {
    release: string
    specification: string
}

export interface ElementWarning {
    code?: string | null
    elementId?: string | null
    message?: string | null
}

/** One link of the element graph: from a dependent to what it depends on. */
export interface Edge {
    from: string
    to: string
    /** parent, assumes, or the trace verb as written */
    kind: string
}

/** The elements a document release carries, each stamped with where it came from. */
export function elementsOf (release?: DocumentRelease | null): TaskElement[] {
    const idx = (release?.document as any)?.elements
    const list = Array.isArray(idx?.elements) ? idx.elements as Element[] : []
    const spec = release?.document?.specification ?? ''
    return list.filter(e => !!e?.id).map(e => ({ ...e, release: release?.uuid ?? '', specification: spec }))
}

/** The index's warnings about one element. */
export function warningsOf (release: DocumentRelease | null | undefined, id: string): ElementWarning[] {
    const w = (release?.document as any)?.elements?.warnings
    return Array.isArray(w) ? (w as ElementWarning[]).filter(x => x?.elementId === id) : []
}

/**
 * The task's elements: the union over its newest release of each specification, as the server's
 * AgentTask.elements has them. Documents arrive newest first, so the first of each type wins.
 */
export function taskElementsOf (documents: DocumentRelease[] | null | undefined): TaskElement[] {
    const seen = new Set<string>()
    const out: TaskElement[] = []
    for (const d of documents ?? []) {
        const spec = d?.document?.specification
        if (!spec || seen.has(spec) || ['PENDING', 'CANCELLED', 'REJECTED'].includes(d?.lifecycle ?? '')) continue
        seen.add(spec)
        out.push(...elementsOf(d))
    }
    return out
}

/** The edges an element points out along: its parent, every trace, every assumption. */
export function linksOut (e?: Element | null): Edge[] {
    if (!e?.id) return []
    const out: Edge[] = []
    if (e.parent) out.push({ from: e.id, to: e.parent, kind: 'parent' })
    for (const l of e.traces ?? []) if (l?.target) out.push({ from: e.id, to: l.target, kind: l.verb || 'traces' })
    for (const a of e.assumes ?? []) if (a) out.push({ from: e.id, to: a, kind: 'assumes' })
    return out
}

/** The edges pointing at an element from the given elements: who depends on it directly. */
export function linksIn (elements: Element[], id: string): Edge[] {
    return elements.flatMap(e => linksOut(e)).filter(edge => edge.to === id)
}

/** Links in and out per element id, over the given elements. */
export function linkCounts (elements: Element[]): Map<string, { in: number, out: number }> {
    const counts = new Map<string, { in: number, out: number }>()
    const at = (id: string) => {
        if (!counts.has(id)) counts.set(id, { in: 0, out: 0 })
        return counts.get(id)!
    }
    for (const e of elements) {
        if (!e?.id) continue
        const edges = linksOut(e)
        at(e.id).out += edges.length
        for (const edge of edges) at(edge.to).in++
    }
    return counts
}

/**
 * Open findings that name an element, per element id: over the newest REVIEW_FINDINGS and
 * TEST_REPORT rounds, which carry forward everything still open.
 */
export function findingsByElement (documents: DocumentRelease[] | null | undefined): Map<string, Finding[]> {
    const out = new Map<string, Finding[]>()
    for (const spec of ['REVIEW_FINDINGS', 'TEST_REPORT']) {
        for (const f of sortFindings(openFindingsOf(latestRound(documents, spec)))) {
            const id = (f?.location as any)?.element
            if (!id) continue
            if (!out.has(id)) out.set(id, [])
            out.get(id)!.push(f)
        }
    }
    return out
}

/** The element a finding names, or null. */
export function findingElement (f?: Finding | null): string | null {
    const id = (f?.location as any)?.element
    return typeof id === 'string' && id ? id : null
}

export interface ElementVersion {
    release?: string | null
    round?: number | null
    specification?: string | null
    contentDigest?: string | null
    line?: number | null
    changed?: boolean | null
}

export interface HistoryRow {
    round: number | null
    mark: 'first' | 'changed' | 'same'
    /** The file at that round's commit, at the element's line; null when the host is unknown. */
    url: string | null
    release: string | null
}

/**
 * The element's history as rows: the round it first appears in, then whether each later round
 * changed it, with a link to the file at that round's commit when the release is one of the task's
 * documents and its host is one we can build a link for.
 */
export function historyRows (versions: ElementVersion[] | null | undefined,
    documents: DocumentRelease[] | null | undefined): HistoryRow[] {
    const byUuid = new Map((documents ?? []).filter(d => d?.uuid).map(d => [d.uuid as string, d]))
    return (versions ?? []).map((v, i) => {
        const doc = v?.release ? byUuid.get(v.release) : undefined
        const file = doc ? documentFileUrl(doc) : null
        return {
            round: typeof v?.round === 'number' ? v.round : null,
            mark: i === 0 ? 'first' : (v?.changed ? 'changed' : 'same'),
            url: file ? (typeof v?.line === 'number' ? `${file}#L${v.line}` : file) : null,
            release: v?.release ?? null,
        }
    })
}

/** The document release among a task's documents that defines an element in its newest round. */
export function documentDefining (documents: DocumentRelease[] | null | undefined, id: string): DocumentRelease | null {
    const el = taskElementsOf(documents).find(e => e.id === id)
    if (!el) return null
    return (documents ?? []).find(d => d?.uuid === el.release) ?? null
}
