import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { withoutHtmlComments } from '@/utils/specText'
import { BOM_SCAN_STATES } from '@/utils/celConditionBuilder'

/**
 * The CEL help popover of CelExpressionBuilder.vue lists the release policy inputs by hand, and
 * the two docs pages that list release.* inputs repeat them. This pins the BOM scan state inputs
 * in both places and ties the docs to the builder's list of states. The second describe reads
 * documentation_site, so it needs the full checkout (a ui/-only archive fails it).
 */
const code = withoutHtmlComments(readFileSync(fileURLToPath(new URL('./CelExpressionBuilder.vue', import.meta.url)), 'utf8'))

/** The lines of an array literal, from its declaration to the first line that is a bare `]`. */
function arrayLines (decl: string): string[] {
    const lines = code.split('\n')
    const start = lines.findIndex(l => l.startsWith(decl))
    expect(start).toBeGreaterThanOrEqual(0)
    const end = lines.findIndex((l, i) => i > start && l === ']')
    expect(end).toBeGreaterThan(start)
    return lines.slice(start + 1, end)
}

const variableLines = arrayLines('const baseVariableDocs')
const names = variableLines.flatMap(l => [...l.matchAll(/name: '([^']+)'/g)].map(m => m[1]))
const releaseNames = names.filter(n => n.startsWith('release.'))
const descOf = (name: string): string => {
    const line = variableLines.find(l => l.includes(`name: '${name}',`)) ?? ''
    return line.match(/desc: '((?:[^'\\]|\\.)*)'/)?.[1] ?? ''
}
const examples = arrayLines('const baseExampleDocs')
    .map(l => l.trim().replace(/,$/, ''))
    .map(l => l.slice(1, -1).replace(/\\'/g, '\''))

const BOM_SCAN_NAMES = ['release.bomScanState', 'release.scannableBoms', 'release.notScannableBoms', 'release.notScannableReasons']
const REASONS = ['NO_COMPONENTS', 'ONLY_ROOT', 'ALL_TERMINAL', 'NO_PURL_OR_CPE']

/** Whether every item occurs in text, each after the one before it. */
function inOrder (text: string, items: readonly string[]): boolean {
    let from = 0
    for (const item of items) {
        const at = text.indexOf(item, from)
        if (at < 0) return false
        from = at + item.length
    }
    return true
}

describe('the CEL help popover', () => {
    it('lists exactly these release inputs, in this order', () => {
        // A new activation key is added both here and in baseVariableDocs.
        expect(releaseNames).toEqual([
            'release.lifecycle', 'release.version', 'release.branchType', 'release.firstScanned',
            'release.criticalVulns', 'release.highVulns', 'release.mediumVulns', 'release.lowVulns',
            'release.unassignedVulns', 'release.securityViolations', 'release.operationalViolations',
            'release.licenseViolations',
            'release.bomScanState', 'release.scannableBoms', 'release.notScannableBoms', 'release.notScannableReasons',
            'release.approvals', 'release.anyApproved', 'release.anyDisapproved', 'release.component',
            'release.dependencies', 'release.dependencies[].lifecycle', 'release.dependencies[].maturity',
            'release.dependencies[].supported', 'release.dependencies[].specification',
            'release.dependencies[].external', 'release.dependencies[].version',
            'release.dependencies[].componentName',
            'release.agentSessions', 'release.agentSessions[].uuid', 'release.agentSessions[].agent',
            'release.agentSessions[].agentName', 'release.agentSessions[].status',
            'release.agentSessions[].hasFailedPolicy', 'release.agentSessions[].hasAwaitingPolicy',
            'release.agentSessions[].failedPolicies', 'release.agentSessions[].awaitingPolicies',
            'release.commits', 'release.commits[].commit', 'release.commits[].agent',
            'release.commits[].agentSession', 'release.commits[].signature.state',
            'release.commits[].signature.format', 'release.commits[].signature.signedByOwnerType',
            'release.commits[].signature.keyFingerprint', 'release.commits[].attribution.state',
            'release.commits[].attribution.resolved', 'release.commits[].attribution.rejected',
            'release.commits[].attribution.reason',
            'release.headCommit', 'release.headCommit.commit', 'release.headCommit.commitEmail',
            'release.headCommit.agent', 'release.headCommit.signature.state',
            'release.headCommit.signature.signedByOwnerType', 'release.headCommit.attribution.state'
        ])
    })

    it.each(BOM_SCAN_NAMES)('lists %s exactly once', (name) => {
        expect(names.filter(n => n === name)).toHaveLength(1)
    })

    it('puts the four BOM scan rows between release.licenseViolations and release.approvals', () => {
        const at = names.indexOf('release.licenseViolations')
        expect(names.slice(at, at + 6)).toEqual(['release.licenseViolations', ...BOM_SCAN_NAMES, 'release.approvals'])
    })

    it('describes the states, the reasons, the pairing and the creation answer', () => {
        const state = descOf('release.bomScanState')
        expect(inOrder(state, ['NO_BOMS', 'ALL_SCANNABLE', 'SOME_NOT_SCANNABLE', 'ALL_NOT_SCANNABLE'])).toBe(true)
        expect(state).toContain('release.firstScanned == true')
        expect(inOrder(descOf('release.notScannableReasons'), REASONS)).toBe(true)
        expect(descOf('release.scannableBoms')).toContain('refuses creation')
    })

    it('lists the three BOM scan examples right after the firstScanned example', () => {
        const at = examples.findIndex(e => e.includes('release.firstScanned == true'))
        expect(at).toBeGreaterThanOrEqual(0)
        expect(examples.slice(at + 1, at + 4)).toEqual([
            'release.bomScanState == "ALL_NOT_SCANNABLE"',
            'release.scannableBoms >= 1',
            'release.bomScanState == "ALL_NOT_SCANNABLE" && !("NO_COMPONENTS" in release.notScannableReasons)'
        ])
    })

    it('offers the BOM Scan State clause in the visual builder', () => {
        const options = code.match(/const conditionTypeOptions = computed\(\(\) => \[[\s\S]*?\n\]\)/)?.[0] ?? ''
        expect(options).toContain('value: \'BOM_SCAN_STATE\'')
        expect(code).toContain('cond.type === \'BOM_SCAN_STATE\'')
    })
})

describe('the docs pages list the same BOM scan inputs', () => {
    const DOCS = fileURLToPath(new URL('../../../documentation_site/docs/integrations/', import.meta.url))
    const doc = (f: string): string => readFileSync(DOCS + f, 'utf8')

    it('githubValidate.md has one table row per input, in order, with the states and the reasons', () => {
        const rows = doc('githubValidate.md').split('\n')
        const rowOf = (name: string): string[] => rows.filter(l => l.startsWith(`| \`${name}\` |`))
        BOM_SCAN_NAMES.forEach(n => expect(rowOf(n)).toHaveLength(1))
        const at = BOM_SCAN_NAMES.map(n => rows.indexOf(rowOf(n)[0]))
        expect(at).toEqual([...at].sort((a, b) => a - b))
        expect(inOrder(rowOf('release.bomScanState')[0], BOM_SCAN_STATES)).toBe(true)
        expect(inOrder(rowOf('release.notScannableReasons')[0], REASONS)).toBe(true)
    })

    it('githubActionsTriggers.md has one line with the inputs, the states and the reasons, each in order', () => {
        const lines = doc('githubActionsTriggers.md').split('\n').filter(l => BOM_SCAN_NAMES.every(n => l.includes(n)))
        expect(lines).toHaveLength(1)
        expect(inOrder(lines[0], BOM_SCAN_NAMES)).toBe(true)
        expect(inOrder(lines[0], BOM_SCAN_STATES)).toBe(true)
        expect(inOrder(lines[0], REASONS)).toBe(true)
    })

    it('names the states the builder knows, and no other, on both pages', () => {
        const stateWords = (text: string): string[] =>
            [...new Set(text.match(/\b(?:NO_BOMS|ALL_SCANNABLE|SOME_NOT_SCANNABLE|ALL_NOT_SCANNABLE|[A-Z_]*SCANNABLE[A-Z_]*)\b/g) ?? [])]
        for (const page of ['githubValidate.md', 'githubActionsTriggers.md']) {
            const line = doc(page).split('\n').find(l => l.includes('`release.bomScanState`') && l.includes('`NO_BOMS`')) ?? ''
            expect(stateWords(line)).toEqual([...BOM_SCAN_STATES])
        }
    })
})
