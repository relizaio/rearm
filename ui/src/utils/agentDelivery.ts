// A task's linked PRs as the board shows them (task 9af9d722): a task whose roles have passed waits
// in DELIVERING until every linked PR has merged. Pure, so the chip rules are testable alone.

export interface PrChip {
    url: string
    /** owner/repo/pull/N, or the URL's tail */
    label: string
    /** merged | open | closed | unregistered */
    state: string
    /** naive-ui tag type */
    type: 'success' | 'warning' | 'error' | 'default'
    title: string
}

export function shortPr (url: string): string {
    const parts = String(url ?? '').replace(/\/+$/, '').split('/')
    return parts.slice(-4).join('/')
}

/**
 * One chip per linked PR, from the server's resolved pullRequests when present; a task read without
 * them (an older query) still shows its prUrls, as unknown.
 */
export function prChips (task: any): PrChip[] {
    const resolved: any[] = task?.pullRequests ?? []
    if (resolved.length) {
        return resolved.map((pr: any) => {
            if (!pr.registered) {
                return { url: pr.url, label: shortPr(pr.url), state: 'unregistered', type: 'default',
                    title: 'CI has not reported this PR, so the board cannot see it merge' }
            }
            const s = String(pr.state ?? '').toUpperCase()
            if (s === 'MERGED') {
                return { url: pr.url, label: shortPr(pr.url), state: 'merged', type: 'success',
                    title: `merged${pr.targetBranch ? ' into ' + pr.targetBranch : ''}${pr.mergedDate ? ' · ' + pr.mergedDate : ''}` }
            }
            if (s === 'CLOSED') {
                return { url: pr.url, label: shortPr(pr.url), state: 'closed', type: 'error',
                    title: 'closed without merging' }
            }
            return { url: pr.url, label: shortPr(pr.url), state: 'open', type: 'warning',
                title: `open${pr.targetBranch ? ' against ' + pr.targetBranch : ''}` }
        })
    }
    return (task?.prUrls ?? []).map((url: string) => ({ url, label: shortPr(url), state: 'linked', type: 'default',
        title: 'linked PR' }))
}
