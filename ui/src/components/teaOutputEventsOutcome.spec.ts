import { describe, it, expect, vi } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import ts from 'typescript'
import { withoutHtmlComments } from '@/utils/specText'
import { teaProfilesAvailable } from '@/utils/teaProfile'
import {
    outputEventTypeLabel, teaActionNames, teaConfirmBeforeAutoPublish, teaConfirmBeforeGlobalAutoPublish,
    teaOrgPublicBadgeTitle, teaOrgPublicSources, teaPublicBadgeTitle, teaPublishWiringAdded,
} from '@/utils/teaOutputEvents'

/**
 * What the TEA-13 save paths and badges DO (test run 1, T-1, T-2 and T-5). teaOutputEventsWiring.spec.ts
 * checks that the components call the helpers in order; this spec runs the components' own functions:
 * each is cut from the .vue source, transpiled, and called with the page state it reads stubbed, so a
 * changed outcome (Cancel saves, a PRIVATE scope blocks, the delta reversed, the cached view in place of
 * the fresh read, a badge on a hide) fails here.
 */
function source (file: string): string {
    return withoutHtmlComments(readFileSync(fileURLToPath(new URL(file, import.meta.url)), 'utf8'))
}

/** From `start` to the brace that closes the first block opened after it. */
function blockFrom (src: string, start: number): string {
    expect(start).toBeGreaterThanOrEqual(0)
    const open = src.indexOf('{', start)
    let depth = 0
    for (let i = open; i < src.length; i++) {
        if (src[i] === '{') depth++
        else if (src[i] === '}' && --depth === 0) return src.slice(start, i + 1)
    }
    throw new Error('unbalanced block at ' + start)
}

/** The component's function `name` (declared by `decl`), compiled against `refs` for every outer name it reads. */
function fnOf (src: string, decl: string, name: string, refs: Record<string, any>): any {
    return compile(blockFrom(src, src.indexOf(decl)), name, refs)
}

/** The Type column render of a table: the `render` arrow that calls outputEventTypeLabel, after `table`. */
function typeRenderOf (src: string, table: string, refs: Record<string, any>): (row: any) => any {
    const at = src.indexOf(table)
    expect(at, table).toBeGreaterThanOrEqual(0)
    const label = src.indexOf('outputEventTypeLabel(outputTriggerTypeOptions, row.type)', at)
    const arrow = src.lastIndexOf('(row: any) => {', label)
    expect(arrow, 'render of ' + table).toBeGreaterThan(at)
    return compile('const typeRender = ' + blockFrom(src, arrow), 'typeRender', refs)
}

function compile (code: string, name: string, refs: Record<string, any>): any {
    const js = ts.transpileModule(code, { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText
    return new Function('refs', `with (refs) { ${js}\nreturn ${name} }`)(refs)
}

const view = (visibility: string, source = 'COMPONENT', publishedReleases = 2) => ({
    effective: { status: 'RESOLVED', source, sourceObject: 'c1', profile: { publishing: 'ENABLED', visibility } },
    publishedReleases,
})
const PUBLIC = view('PUBLIC')
const PRIVATE = view('PRIVATE')
const quietConsole = { error: () => {}, log: () => {}, warn: () => {} }
const tag = (h: any) => (typeof h === 'object' && h ? h.children.find((c: any) => typeof c === 'object' && c?.tag === 'NTag') : undefined)
const hStub = (tagName: any, props: any, children?: any) => (Array.isArray(props) && children === undefined
    ? { tag: tagName, props: {}, children: props }
    : { tag: tagName, props, children: Array.isArray(children) ? children : [children] })

describe('ComponentView: confirmTeaPublishWiring after the fresh read (T-1)', () => {
    const code = source('./ComponentView.vue')
    const publishAction = { uuid: 'a', name: 'Publish it', type: 'TEA_PUBLISH' }

    /** A rig over the stored and staged component, the fresh answer, the cached badge view and the dialog's answer. */
    function rig (o: { stored: any, staged: any, fresh?: any, cached?: any, readFails?: boolean, answer?: any, edition?: string }) {
        const queries: any[] = []
        const fire = vi.fn(async (_opts: any) => o.answer)
        const notify = vi.fn()
        const refs = {
            teaProfilesAvailable, teaPublishWiringAdded, teaConfirmBeforeAutoPublish, teaActionNames,
            myUser: { installationType: o.edition ?? 'SAAS' },
            policyGlobalInputEvents: { value: [] },
            policyGlobalOutputEvents: { value: [] },
            componentData: { value: o.stored },
            updatedComponent: { value: o.staged },
            graphqlClient: {
                query: async (q: any) => {
                    queries.push(q)
                    if (o.readFails) throw new Error('read refused')
                    return { data: { teaProfileEditorView: o.fresh } }
                },
            },
            graphqlQueries: { TeaProfileEditorViewGql: 'TEA_PROFILE_EDITOR_VIEW' },
            orguuid: { value: 'org1' },
            componentUuid: 'comp1',
            words: { value: { component: 'component' } },
            teaPerspectiveNames: () => ({}),
            // the badge's cached view: the gate must not read it
            teaView: { value: o.cached ?? null },
            notify,
            Swal: { fire },
            console: quietConsole,
        }
        const confirm = fnOf(code, 'async function confirmTeaPublishWiring (): Promise<boolean> {', 'confirmTeaPublishWiring', refs)
        return { confirm, queries, fire, notify }
    }

    const without = { outputTriggers: [] }
    const withPublish = { outputTriggers: [publishAction] }

    it('a new publish on a PUBLIC scope asks; Cancel and a dismissed dialog send nothing, confirm proceeds', async () => {
        for (const [answer, expected] of [[{ isConfirmed: true }, true], [{ isConfirmed: false }, false],
            [{ isDismissed: true }, false], [undefined, false]] as const) {
            const r = rig({ stored: without, staged: withPublish, fresh: PUBLIC, answer })
            expect(await r.confirm()).toBe(expected)
            expect(r.fire).toHaveBeenCalledTimes(1)
            expect(r.fire.mock.calls[0][0]).toMatchObject({ title: 'Publish automatically to the public?', showCancelButton: true })
            expect(r.fire.mock.calls[0][0].text).toContain('Action(s) Publish it publish releases of this component')
        }
    })

    it('reads the profile fresh, no-cache, for this component', async () => {
        const r = rig({ stored: without, staged: withPublish, fresh: PUBLIC, answer: { isConfirmed: true } })
        await r.confirm()
        expect(r.queries).toEqual([{
            query: 'TEA_PROFILE_EDITOR_VIEW',
            variables: { org: 'org1', scope: 'COMPONENT', object: 'comp1' },
            fetchPolicy: 'no-cache',
        }])
    })

    it('a PRIVATE scope proceeds without a dialog', async () => {
        const r = rig({ stored: without, staged: withPublish, fresh: PRIVATE, answer: { isConfirmed: false } })
        expect(await r.confirm()).toBe(true)
        expect(r.fire).not.toHaveBeenCalled()
        expect(r.queries.length).toBe(1)
    })

    it('follows the fresh answer, not the view the badge cached', async () => {
        const flippedPublic = rig({ stored: without, staged: withPublish, fresh: PUBLIC, cached: PRIVATE, answer: { isConfirmed: false } })
        expect(await flippedPublic.confirm()).toBe(false)
        expect(flippedPublic.fire).toHaveBeenCalledTimes(1)
        const flippedPrivate = rig({ stored: without, staged: withPublish, fresh: PRIVATE, cached: PUBLIC, answer: { isConfirmed: false } })
        expect(await flippedPrivate.confirm()).toBe(true)
        expect(flippedPrivate.fire).not.toHaveBeenCalled()
    })

    it('compares staged against stored: a removed or a kept publish neither reads nor asks', async () => {
        const removed = rig({ stored: withPublish, staged: without, fresh: PUBLIC, answer: { isConfirmed: false } })
        expect(await removed.confirm()).toBe(true)
        const renamed = rig({ stored: withPublish, staged: { outputTriggers: [{ ...publishAction, name: 'Renamed' }] }, fresh: PUBLIC, answer: { isConfirmed: false } })
        expect(await renamed.confirm()).toBe(true)
        for (const r of [removed, renamed]) {
            expect(r.queries).toEqual([])
            expect(r.fire).not.toHaveBeenCalled()
        }
    })

    it('a failed read sends nothing and says so', async () => {
        const r = rig({ stored: without, staged: withPublish, readFails: true, answer: { isConfirmed: true } })
        expect(await r.confirm()).toBe(false)
        expect(r.fire).not.toHaveBeenCalled()
        expect(r.notify).toHaveBeenCalledTimes(1)
        expect(r.notify.mock.calls[0][0]).toBe('error')
        expect(r.notify.mock.calls[0][2]).toContain('nothing was saved')
    })

    it('CE: nothing to confirm, nothing read', async () => {
        const r = rig({ stored: without, staged: withPublish, fresh: PUBLIC, answer: { isConfirmed: false }, edition: 'OSS' })
        expect(await r.confirm()).toBe(true)
        expect(r.queries).toEqual([])
    })

    it('save() sends only when the gate answers true, and answers what it did', async () => {
        for (const ok of [true, false]) {
            const submitComponentSave = vi.fn(async () => {})
            const save = fnOf(code, 'async function save (): Promise<boolean> {', 'save', {
                confirmTeaPublishWiring: async () => ok, submitComponentSave, formRevision: { value: 7 },
            })
            expect(await save()).toBe(ok)
            expect(submitComponentSave.mock.calls).toEqual(ok ? [[7]] : [])
        }
    })
})

describe('OrgSettings: addGlobalOutputEvent after the fresh read (T-2)', () => {
    const code = source('./OrgSettings.vue')
    const PUBLIC_ORG = { view: view('PUBLIC', 'ORGANIZATION', 4), rows: [] }
    const PRIVATE_ORG = { view: view('PRIVATE', 'ORGANIZATION', 0), rows: [] }

    function rig (o: { draft: any, stored?: any[], read?: any, answer?: any, edition?: string }) {
        const fire = vi.fn(async (_opts: any) => o.answer)
        const notify = vi.fn()
        const loadTeaOrgExposure = vi.fn(async () => o.read ?? null)
        const saveGlobalOutputEvents = vi.fn(() => Promise.resolve())
        const refs = {
            teaProfilesAvailable, teaConfirmBeforeGlobalAutoPublish, teaOrgPublicSources,
            globalOutputEvent: { value: { ...o.draft } },
            commonFunctions: { deepCopy: (x: any) => JSON.parse(JSON.stringify(x)) },
            globalSnapshotMode: { value: 'NONE' },
            validateOutputTrigger: () => ({ valid: true }),
            globalOutputEvents: { value: (o.stored ?? []).map(e => ({ ...e })) },
            myUser: { value: { installationType: o.edition ?? 'SAAS' } },
            loadTeaOrgExposure,
            Swal: { fire },
            notify,
            pendingActionAttachBranch: { value: null },
            pendingActionAttachUuidsBefore: { value: [] },
            saveGlobalOutputEvents,
            globalInputEvent: { value: {} },
            resetGlobalOutputEvent: vi.fn(),
            showCreateGlobalOutputEventModal: { value: true },
        }
        const add = fnOf(code, 'async function addGlobalOutputEvent () {', 'addGlobalOutputEvent', refs)
        return { add, refs, fire, notify, loadTeaOrgExposure, saveGlobalOutputEvents }
    }
    const pushedAndSaved = (r: any) => r.saveGlobalOutputEvents.mock.calls.length === 1 && !r.refs.showCreateGlobalOutputEventModal.value
    const newPublish = { name: 'Global publish', type: 'TEA_PUBLISH' }

    it('Cancel on a PUBLIC organization keeps the modal open with nothing pushed and nothing saved', async () => {
        for (const answer of [{ isConfirmed: false }, { isDismissed: true }, undefined]) {
            const r = rig({ draft: newPublish, read: PUBLIC_ORG, answer })
            await r.add()
            expect(r.fire).toHaveBeenCalledTimes(1)
            expect(r.fire.mock.calls[0][0].text).toContain("Policy-wide action 'Global publish'")
            expect(r.refs.globalOutputEvents.value).toEqual([])
            expect(r.saveGlobalOutputEvents).not.toHaveBeenCalled()
            expect(r.refs.showCreateGlobalOutputEventModal.value).toBe(true)
        }
    })

    it('confirm pushes and saves', async () => {
        const r = rig({ draft: newPublish, read: PUBLIC_ORG, answer: { isConfirmed: true } })
        await r.add()
        expect(r.refs.globalOutputEvents.value.map((e: any) => e.name)).toEqual(['Global publish'])
        expect(pushedAndSaved(r)).toBe(true)
    })

    it('nothing PUBLIC in the organization: no dialog, saved', async () => {
        const r = rig({ draft: newPublish, read: PRIVATE_ORG, answer: { isConfirmed: false } })
        await r.add()
        expect(r.loadTeaOrgExposure).toHaveBeenCalledTimes(1)
        expect(r.fire).not.toHaveBeenCalled()
        expect(pushedAndSaved(r)).toBe(true)
    })

    it('a failed read sends nothing and says so', async () => {
        const r = rig({ draft: newPublish, read: null, answer: { isConfirmed: true } })
        await r.add()
        expect(r.fire).not.toHaveBeenCalled()
        expect(r.notify.mock.calls.map((c: any[]) => c[0])).toEqual(['error'])
        expect(r.refs.globalOutputEvents.value).toEqual([])
        expect(r.saveGlobalOutputEvents).not.toHaveBeenCalled()
        expect(r.refs.showCreateGlobalOutputEventModal.value).toBe(true)
    })

    it('editing a stored TEA_PUBLISH neither reads nor asks; retyping another action to TEA_PUBLISH does', async () => {
        const kept = rig({ draft: { uuid: 'p', name: 'Renamed', type: 'TEA_PUBLISH' }, stored: [{ uuid: 'p', name: 'Old', type: 'TEA_PUBLISH' }],
            read: PUBLIC_ORG, answer: { isConfirmed: false } })
        await kept.add()
        expect(kept.loadTeaOrgExposure).not.toHaveBeenCalled()
        expect(kept.fire).not.toHaveBeenCalled()
        expect(kept.refs.globalOutputEvents.value.map((e: any) => e.name)).toEqual(['Renamed'])
        expect(pushedAndSaved(kept)).toBe(true)

        const retyped = rig({ draft: { uuid: 'p', name: 'Mail', type: 'TEA_PUBLISH' }, stored: [{ uuid: 'p', name: 'Mail', type: 'EMAIL_NOTIFICATION' }],
            read: PUBLIC_ORG, answer: { isConfirmed: false } })
        await retyped.add()
        expect(retyped.fire).toHaveBeenCalledTimes(1)
        expect(retyped.refs.globalOutputEvents.value[0].type).toBe('EMAIL_NOTIFICATION')
        expect(retyped.saveGlobalOutputEvents).not.toHaveBeenCalled()
    })

    it('a TEA Hide, and any action on CE, neither reads nor asks', async () => {
        for (const r of [rig({ draft: { name: 'Hide', type: 'TEA_HIDE' }, read: PUBLIC_ORG, answer: { isConfirmed: false } }),
            rig({ draft: newPublish, read: PUBLIC_ORG, answer: { isConfirmed: false }, edition: 'OSS' })]) {
            await r.add()
            expect(r.loadTeaOrgExposure).not.toHaveBeenCalled()
            expect(r.fire).not.toHaveBeenCalled()
            expect(pushedAndSaved(r)).toBe(true)
        }
    })
})

describe('OrgSettings: loadTeaOrgExposure reads both documents fresh (T-2)', () => {
    const code = source('./OrgSettings.vue')

    function rig (o: { writable?: boolean, fails?: boolean, edition?: string }) {
        const queries: any[] = []
        const refs = {
            teaProfilesAvailable, teaOrgPublicSources,
            myUser: { value: { installationType: o.edition ?? 'SAAS' } },
            isWritable: { value: o.writable ?? true },
            teaOrgSources: { value: 'stale' as any },
            orgResolved: { value: 'org1' },
            graphqlQueries: { TeaProfileEditorViewGql: 'VIEW', TeaProfilesOfOrgGql: 'ROWS' },
            graphqlClient: {
                query: async (q: any) => {
                    queries.push(q)
                    if (o.fails) throw new Error('read refused')
                    return q.query === 'VIEW'
                        ? { data: { teaProfileEditorView: view('PRIVATE', 'ORGANIZATION', 0) } }
                        : { data: { teaProfilesOfOrg: [{ scope: 'COMPONENT', mode: 'OVERRIDE', publishing: 'ENABLED', visibility: 'PUBLIC' }] } }
                },
            },
            console: quietConsole,
        }
        const load = fnOf(code, 'async function loadTeaOrgExposure (): Promise<TeaOrgExposureRead | null> {', 'loadTeaOrgExposure', refs)
        return { load, refs, queries }
    }

    it('sends the organization view and the profile rows, both no-cache, and keeps the sources for the badge', async () => {
        const r = rig({})
        const read = await r.load()
        expect(r.queries).toEqual([
            { query: 'VIEW', variables: { org: 'org1', scope: 'ORGANIZATION', object: null }, fetchPolicy: 'no-cache' },
            { query: 'ROWS', variables: { org: 'org1' }, fetchPolicy: 'no-cache' },
        ])
        expect(read.rows.length).toBe(1)
        expect(r.refs.teaOrgSources.value).toEqual({ organization: false, perspectives: 0, components: 1, publishedReleases: 0 })
    })

    it('answers null with the badge off when not sent (not a writer, CE) or failed', async () => {
        for (const o of [{ writable: false }, { edition: 'OSS' }, { fails: true }]) {
            const r = rig(o)
            expect(await r.load()).toBeNull()
            expect(r.refs.teaOrgSources.value).toBeNull()
            if (!o.fails) expect(r.queries).toEqual([])
        }
    })
})

describe('the PUBLIC badge is on TEA_PUBLISH rows only, and per policy rule (T-5)', () => {
    const OPTIONS = [{ label: 'Email Notification', value: 'EMAIL_NOTIFICATION' }]

    describe('ComponentView', () => {
        const code = source('./ComponentView.vue')
        const badgeRefs = (cached: any, staged: string[] = []) => ({
            teaPublicBadgeTitle,
            teaView: { value: cached },
            teaPerspectiveNames: () => ({}),
            words: { value: { component: 'component' } },
            teaStagedWiring: { value: staged },
        })
        const actionBadge = (cached: any) => fnOf(code, 'function teaActionBadgeTitle (row: any): string | null {', 'teaActionBadgeTitle', badgeRefs(cached))

        it('teaActionBadgeTitle: a TEA_PUBLISH row on a PUBLIC scope only', () => {
            const onPublic = actionBadge(PUBLIC)
            expect(onPublic({ type: 'TEA_PUBLISH' })).toBe('Publishes to the public: this component resolves to the PUBLIC TEA profile of component.')
            for (const type of ['TEA_HIDE', 'EMAIL_NOTIFICATION', 'VDR_SNAPSHOT_ARTIFACT']) expect(onPublic({ type })).toBeNull()
            expect(actionBadge(PRIVATE)({ type: 'TEA_PUBLISH' })).toBeNull()
            expect(actionBadge(null)({ type: 'TEA_PUBLISH' })).toBeNull()
        })

        it('both action tables render the tag beside the label on a TEA_PUBLISH row only', () => {
            for (const table of ['const outputTriggerTableFields: DataTableColumns<any> = [', 'const outputTriggerTableFieldsReadOnly: DataTableColumns<any> = [']) {
                const render = typeRenderOf(code, table, {
                    outputEventTypeLabel, outputTriggerTypeOptions: OPTIONS, teaActionBadgeTitle: actionBadge(PUBLIC), h: hStub, NTag: 'NTag',
                })
                const cell = render({ type: 'TEA_PUBLISH' })
                expect(cell.children[0]).toBe('TEA Publish')
                expect(tag(cell).props).toMatchObject({ type: 'error', 'data-testid': 'tea-public-badge' })
                expect(render({ type: 'TEA_HIDE' })).toBe('TEA Hide')
                expect(render({ type: 'EMAIL_NOTIFICATION' })).toBe('Email Notification')
            }
        })

        it('teaPolicyRuleBadgeTitle: only the rule whose own policy piece fires a publish', () => {
            const staged = ['action:a', 'policy:g4:false:g', 'rule:r:true:a']
            const ruleBadge = (cached: any) => fnOf(code, 'function teaPolicyRuleBadgeTitle (ruleUuid: string): string | null {', 'teaPolicyRuleBadgeTitle', badgeRefs(cached, staged))
            expect(ruleBadge(PUBLIC)('g4')).toBe('Publishes to the public: this component resolves to the PUBLIC TEA profile of component.')
            for (const other of ['g5', 'g', 'r', 'a']) expect(ruleBadge(PUBLIC)(other)).toBeNull()
            expect(ruleBadge(PRIVATE)('g4')).toBeNull()
        })
    })

    describe('OrgSettings', () => {
        const code = source('./OrgSettings.vue')
        const render = (sources: any) => typeRenderOf(code, 'const globalOutputEventTableFields: DataTableColumns<any> = [', {
            outputEventTypeLabel, outputTriggerTypeOptions: OPTIONS, teaOrgPublicBadgeTitle, teaOrgSources: { value: sources }, h: hStub, NTag: 'NTag',
        })

        it('the policy table renders the tag on a TEA_PUBLISH row only, while something is PUBLIC', () => {
            const onPublic = render({ organization: true, perspectives: 0, components: 0, publishedReleases: 0 })
            const cell = onPublic({ type: 'TEA_PUBLISH' })
            expect(cell.children[0]).toBe('TEA Publish')
            expect(tag(cell).props).toMatchObject({ type: 'error', 'data-testid': 'tea-public-badge', title: 'Publishes to the public: the organization profile is PUBLIC.' })
            expect(onPublic({ type: 'TEA_HIDE' })).toBe('TEA Hide')
            expect(onPublic({ type: 'EMAIL_NOTIFICATION' })).toBe('Email Notification')
            expect(render(null)({ type: 'TEA_PUBLISH' })).toBe('TEA Publish')
        })
    })
})
