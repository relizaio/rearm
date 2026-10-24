import { describe, expect, it } from 'vitest'
import { readFileSync, readdirSync } from 'fs'
import { join, relative } from 'path'
import { fileURLToPath } from 'url'

/**
 * TPF-101 test 2.3 (TPF-74 child 2, ARCHITECTURE round 2 2.5 and 3.6): the readers of the org
 * component and product lists read only fields the componentRefs rows carry (uuid name type status
 * org vcs). This is a TEXTUAL pin over the sources, not a behavioural test: it fixes the inventory
 * (a new reader of the getters or of state.components is a change to this spec) and checks that no
 * statement around a read names a field the refs do not carry. The behavioural checks are
 * ComponentsOfOrg.spec.ts, ScopedPermissions.spec.ts, componentRefsStore.spec.ts and the existing
 * consumer specs that feed ref-shaped rows.
 */
const SRC = fileURLToPath(new URL('..', import.meta.url))
const source = (rel: string) => readFileSync(join(SRC, rel), 'utf8')

/** Fields of a full component record that the refs never carry. */
const NON_REF_FIELDS = ['effectiveLifecycle', 'versionSchema', 'featureBranchVersioning', 'identifiers',
    'resourceGroup', 'perspectiveDetails', 'createdDate']

/** From the line holding `index` to the next blank line: the statement the read sits in. */
function statementAt (text: string, index: number): string {
    const start = text.lastIndexOf('\n', index) + 1
    const blank = text.slice(start).search(/\n[ \t]*\n/)
    return blank < 0 ? text.slice(start) : text.slice(start, start + blank)
}
/** The definition that starts with `opening`, to the next blank line. */
function definition (text: string, opening: string): string {
    const at = text.indexOf(opening)
    expect(at, opening).toBeGreaterThan(-1)
    return statementAt(text, at)
}
function expectNoNonRefField (slice: string, where: string) {
    for (const f of NON_REF_FIELDS) expect(slice, `${where} reads ${f}`).not.toMatch(new RegExp(`\\b${f}\\b`))
}
const occurrences = (text: string, needle: string) => text.split(needle).length - 1

/** Every non-spec source under ui/src, relative to it. */
function sources (dir = SRC): string[] {
    return readdirSync(dir, { withFileTypes: true }).flatMap(e => {
        const p = join(dir, e.name)
        if (e.isDirectory()) return sources(p)
        return /\.(vue|ts|js|mjs)$/.test(e.name) && !/\.spec\.ts$/.test(e.name) ? [relative(SRC, p)] : []
    })
}

type Getter = 'componentsOfOrg' | 'productsOfOrg'
const BOTH: Getter[] = ['componentsOfOrg', 'productsOfOrg']
/** The fourteen readers of 2.5 and the getters each calls. */
const READERS: Record<string, Getter[]> = {
    'components/AddComponent.vue': BOTH,
    'components/AddComponentBranch.vue': ['productsOfOrg'],
    'components/ApiKeyPermissionsModal.vue': BOTH,
    'components/CliLogin.vue': BOTH,
    'components/ComponentsOfOrg.vue': BOTH,
    'components/CreateArtifact.vue': ['componentsOfOrg'],
    'components/CreateRelease.vue': BOTH,
    'components/CreateSourceCodeEntry.vue': ['componentsOfOrg'],
    'components/DistributionOfOrg.vue': ['productsOfOrg'],
    'components/ExternalKeysPanel.vue': ['productsOfOrg'],
    'components/FederatedTrustRulesPanel.vue': BOTH,
    'components/OrgSettings.vue': BOTH,
    'components/VcsReposOfOrg.vue': ['componentsOfOrg'],
    'components/VulnerabilityAnalysis.vue': BOTH,
}
/** The prop receivers (2.5 p1-p3): the list props they read. */
const PROP_RECEIVERS: Record<string, string[]> = {
    'components/ScopedPermissions.vue': ['props.components', 'props.products'],
    'components/TeamsOfOrg.vue': ['props.components'],
    'components/TeaOrgPanel.vue': ['props.components'],
}

describe('the readers of the org lists read within the ref (TPF-101, textual pin)', () => {
    it('(a) the getter readers are exactly the inventory, each calling its getters', () => {
        const callers: Record<string, Getter[]> = {}
        for (const rel of sources()) {
            if (rel === 'store.ts') continue
            const text = source(rel)
            const called = BOTH.filter(g => new RegExp(`\\b${g}\\(`).test(text))
            if (called.length) callers[rel] = called
        }
        expect(callers).toEqual(READERS)
    })

    it('(a2) state.components is read directly only by the store and BranchView, at two sites', () => {
        const direct = sources().filter(rel => /\bstate\.(components|products)\b/.test(source(rel))).sort()
        expect(direct).toEqual(['components/BranchView.vue', 'store.ts'])
        expect(occurrences(source('components/BranchView.vue'), 'store.state.components.find(')).toBe(2)
    })

    it('(b) no statement around a getter call names a field the refs do not carry', () => {
        for (const [rel, getters] of Object.entries(READERS)) {
            const text = source(rel)
            for (const g of getters) {
                const re = new RegExp(`\\b${g}\\(`, 'g')
                let m: RegExpExecArray | null
                let n = 0
                while ((m = re.exec(text))) {
                    n++
                    expectNoNonRefField(statementAt(text, m.index), `${rel} ${g} #${n}`)
                }
                expect(n, `${rel} ${g}`).toBeGreaterThan(0)
            }
        }
        for (const [rel, reads] of Object.entries(PROP_RECEIVERS)) {
            const text = source(rel)
            for (const r of reads) {
                let at = text.indexOf(r)
                expect(at, `${rel} ${r}`).toBeGreaterThan(-1)
                while (at > -1) {
                    expectNoNonRefField(statementAt(text, at), `${rel} ${r}`)
                    at = text.indexOf(r, at + r.length)
                }
            }
        }
        const target = source('utils/agentBoardTarget.ts')
        expectNoNonRefField(definition(target, 'export function boardTargetOptions'), 'boardTargetOptions')
        expectNoNonRefField(definition(target, 'export function targetOf'), 'targetOf')
    })

    it('(b2) BranchView reads uuid, name and type off the store entries', () => {
        const text = source('components/BranchView.vue')
        const open = text.indexOf('const addedComponent = function (component: any)')
        expect(open).toBeGreaterThan(-1)
        const close = text.indexOf('\n}\n', open)
        expect(close).toBeGreaterThan(open)
        const added = text.slice(open, close)
        expect(added).toContain('store.state.components.find(')
        expect(added).toContain('component: componentDetails,')
        expectNoNonRefField(added, 'BranchView addedComponent')

        const table = text.indexOf('const effectiveDepTableFields')
        expect(table).toBeGreaterThan(-1)
        const typeStart = text.indexOf("title: 'Type',", table)
        expect(typeStart).toBeGreaterThan(table)
        const typeEnd = text.indexOf('title: ', typeStart + "title: 'Type',".length)
        expect(typeEnd).toBeGreaterThan(typeStart)
        const typeColumn = text.slice(typeStart, typeEnd)
        expect(typeColumn).toContain('store.state.components.find(')
        expect(typeColumn).toContain('componentFromStore?.type')
        expectNoNonRefField(typeColumn, 'BranchView Type column')
    })

    it('(c) OrgSettings list definitions read within the ref', () => {
        const text = source('components/OrgSettings.vue')
        for (const d of ['const teaNamedObjects', 'const allComponents = computed', 'const availableComponentsOptions',
            'const availableProductsOptions']) {
            expectNoNonRefField(definition(text, d), `OrgSettings ${d}`)
        }
    })
})
