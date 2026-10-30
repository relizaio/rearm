// @vitest-environment happy-dom
//
// The package settings editor (task TEA-30, design 4.4 row 22), generic over kinds: packageKinds
// answers FDA_PREMARKET and a second kind TEST_KIND, and the editor renders whatever the descriptor
// says -- the parts in order, REQUIRED locked to INCLUDE, the applicable options per part, the
// no-generator tag, the picker in single mode, the save, the removal, the conflict alert, the
// catalog-only reload and the host testid on the picker root, and the Enabled switch: first in the
// fields, off by default, sent on a save, absent from a FOLLOW row which shows the followed state.
// The Inherit mode (UI only): with no stored row the editor opens in INHERIT, every field and the
// Enabled switch read-only at the inherited value, without Save; INHERIT over a stored row asks
// "Inherit again?" (naive's dialog, mocked) and removes the row. The Enabled help follows the value
// the switch shows, the read-only INHERIT and reader displays included. At COMPONENT scope in INHERIT,
// with perspectives and no conflict yet, a muted hint says inheriting can stop with a conflict.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { readFileSync } from 'fs'
import { resolve } from 'path'
import { NSelect, NSwitch } from 'naive-ui'

const query = vi.fn()
const mutate = vi.fn()
const fire = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: (...a: any[]) => mutate(...a) } }))
vi.mock('sweetalert2', () => ({ default: { fire: (...a: any[]) => fire(...a) } }))
const dialogWarning = vi.fn()
vi.mock('naive-ui', async (importOriginal) => {
    const actual: any = await importOriginal()
    return { ...actual, useDialog: () => ({ warning: dialogWarning }) }
})

const { default: Editor } = await import('./PackageSettingsEditor.vue')
const { packageFormFromView, packageSettingsInput } = await import('@/utils/packageSettings')

const FDA = { kind: 'FDA_PREMARKET', parts: [
    { part: 'AGGREGATE_SBOM', requirement: 'REQUIRED', defaultSelection: 'INCLUDE', generatorPresent: true },
    { part: 'VDR', requirement: 'REQUIRED', defaultSelection: 'INCLUDE', generatorPresent: true },
    { part: 'VEX', requirement: 'OPTIONAL', defaultSelection: 'INCLUDE', generatorPresent: true },
    { part: 'NARRATIVE', requirement: 'REQUIRED', defaultSelection: 'INCLUDE', generatorPresent: true },
    { part: 'SUPPORT_ADDENDUM', requirement: 'REQUIRED', defaultSelection: 'INCLUDE', generatorPresent: true },
    { part: 'DEVICE_SUPPORT_STATEMENT', requirement: 'OPTIONAL', defaultSelection: 'EXCLUDE', generatorPresent: false },
    { part: 'RAW_ARTIFACTS', requirement: 'OPTIONAL', defaultSelection: 'EXCLUDE', generatorPresent: true },
] }
const TEST_KIND = { kind: 'TEST_KIND', parts: [
    { part: 'AGGREGATE_SBOM', requirement: 'REQUIRED', defaultSelection: 'INCLUDE', generatorPresent: true },
    { part: 'RAW_ARTIFACTS', requirement: 'OPTIONAL', defaultSelection: 'EXCLUDE', generatorPresent: true },
] }
const CATALOG = [{ name: 'default', resolution: { status: 'RESOLVED', source: 'ORGANIZATION', sourceObject: null,
    profile: { name: 'default', revision: 1 }, conflictingPerspectives: [] } }]

const resolution = { status: 'RESOLVED', source: 'ORGANIZATION', sourceObject: null, conflictingPerspectives: [],
    settings: { sbomProfile: 'default', revision: 2, parts: [] } }
const viewOf = (descriptor: any, changes: Record<string, any> = {}) =>
    ({ stored: null, effective: resolution, parent: resolution, kind: descriptor, ...changes })

const operation = (opts: any) => (opts.query ?? opts.mutation)?.definitions?.[0]?.name?.value
const calls = (name: string) => query.mock.calls.filter(c => operation(c[0]) === name)
let views: Record<string, any> = {}

function answers () {
    query.mockImplementation(async (opts: any) => {
        switch (operation(opts)) {
        case 'PackageKinds': return { data: { packageKinds: [FDA, TEST_KIND] } }
        case 'PackageSettingsEditorView': return { data: { packageSettingsEditorView: views[opts.variables.kind] } }
        case 'SbomProfileCatalog': return { data: { sbomProfileCatalog: CATALOG } }
        default: throw new Error('unexpected query ' + operation(opts))
        }
    })
}

async function mountWith (props: Record<string, any> = {}) {
    const w = mount(Editor, { props: { orgUuid: 'o1', scope: 'COMPONENT', objectUuid: 'c1', isWritable: true,
        installationType: 'SAAS', ...props } })
    await flushPromises()
    return w
}

const select = (w: any, testid: string) =>
    w.findAllComponents(NSelect).find((s: any) => s.attributes('data-testid') === testid)
const partRows = (w: any) => w.findAll('[data-testid="package-parts"] tbody tr')
const radios = (w: any) => w.findAll('[data-testid="package-mode"] input[type="radio"]')
const modeValues = (w: any) => radios(w).map((i: any) => (i.element as HTMLInputElement).value)
const checkedMode = (w: any) => (radios(w).find((i: any) => (i.element as HTMLInputElement).checked)?.element as HTMLInputElement)?.value
async function pickMode (w: any, mode: string) {
    await radios(w).find((i: any) => (i.element as HTMLInputElement).value === mode)!.setValue(true)
    await flushPromises()
}
const enabledSwitch = (w: any) => w.findAllComponents(NSwitch).find((c: any) => c.attributes('data-testid') === 'package-enabled')!
const merged = (c: any) => Boolean((c.vm as any).mergedDisabled)

describe('PackageSettingsEditor', () => {
    beforeEach(() => {
        query.mockReset()
        mutate.mockReset()
        fire.mockReset()
        dialogWarning.mockReset()
        views = { FDA_PREMARKET: viewOf(FDA), TEST_KIND: viewOf(TEST_KIND) }
        answers()
        mutate.mockResolvedValue({ data: { savePackageSettings: { uuid: 's1' }, deletePackageSettings: true } })
    })

    it('offers both kinds in order and re-reads the view when the kind changes, rendering its parts only', async () => {
        const w = await mountWith()
        expect(select(w, 'package-kind').props('options').map((o: any) => o.value)).toEqual(['FDA_PREMARKET', 'TEST_KIND'])
        expect(select(w, 'package-kind').props('options')[0].label).toBe('FDA premarket')
        expect(partRows(w)).toHaveLength(7)
        expect(calls('PackageSettingsEditorView')[0][0].variables).toEqual({ org: 'o1', scope: 'COMPONENT', object: 'c1',
            kind: 'FDA_PREMARKET' })
        await select(w, 'package-kind').vm.$emit('update:value', 'TEST_KIND')
        await flushPromises()
        expect(calls('PackageSettingsEditorView').at(-1)![0].variables.kind).toBe('TEST_KIND')
        expect(partRows(w).map((r: any) => r.attributes('data-testid'))).toEqual(['package-part-AGGREGATE_SBOM',
            'package-part-RAW_ARTIFACTS'])
    })

    it('locks REQUIRED parts at INCLUDE, shows the applicable options, and warns on a missing generator', async () => {
        const w = await mountWith()
        await pickMode(w, 'OVERRIDE')
        for (const part of ['AGGREGATE_SBOM', 'VDR', 'NARRATIVE', 'SUPPORT_ADDENDUM']) {
            expect(select(w, 'package-selection-' + part).props('disabled'), part).toBe(true)
            expect(select(w, 'package-selection-' + part).props('value'), part).toBe('INCLUDE')
            expect(w.find('[data-testid="package-selection-reason-' + part + '"]').text()).toBe('REQUIRED by the kind')
        }
        expect(select(w, 'package-selection-VEX').props('disabled')).toBe(false)
        for (const id of ['package-suppressed-VEX', 'package-in-triage-VEX', 'package-vex-format-VEX', 'package-suppressed-VDR']) {
            expect(select(w, id), id).toBeDefined()
        }
        expect(select(w, 'package-in-triage-VDR')).toBeUndefined()
        expect(select(w, 'package-vex-format-VDR')).toBeUndefined()
        expect(select(w, 'package-suppressed-NARRATIVE')).toBeUndefined()
        const types = () => select(w, 'package-artifact-types-RAW_ARTIFACTS')
        expect(types().props('disabled')).toBe(true)
        await select(w, 'package-selection-RAW_ARTIFACTS').vm.$emit('update:value', 'INCLUDE')
        expect(types().props('disabled')).toBe(false)
        expect(types().props('multiple')).toBe(true)
        const noGen = w.findAll('[data-testid="package-no-generator"]')
        expect(noGen).toHaveLength(1)
        expect(w.find('[data-testid="package-part-DEVICE_SUPPORT_STATEMENT"]').find('[data-testid="package-no-generator"]').exists())
            .toBe(true)
        expect(noGen[0].attributes('title')).toContain('No generator is registered for this part')
    })

    it('puts the picker in single mode on the host testid root, and saves the form', async () => {
        const w = await mountWith()
        const root = w.find('[data-testid="package-sbom-profile"]')
        expect(root.element.tagName).toBe('DIV')
        expect(root.classes()).toContain('sbom-profile-picker-root')
        expect(root.find('[data-testid="sbom-profile-picker"]').exists()).toBe(true)
        expect(select(w, 'sbom-profile-picker').props('multiple')).toBe(false)
        await pickMode(w, 'OVERRIDE')
        await select(w, 'package-selection-VEX').vm.$emit('update:value', 'EXCLUDE')
        await w.find('[data-testid="package-save"]').trigger('click')
        await flushPromises()
        expect(mutate).toHaveBeenCalledTimes(1)
        const vars = mutate.mock.calls[0][0].variables
        const form = { ...packageFormFromView(views.FDA_PREMARKET, 'COMPONENT'), mode: 'OVERRIDE' }
        form.parts.find((p: any) => p.part === 'VEX')!.selection = 'EXCLUDE'
        expect(vars).toEqual({ org: 'o1', scope: 'COMPONENT', object: 'c1', kind: 'FDA_PREMARKET',
            settings: packageSettingsInput(form, 'COMPONENT') })
        expect(w.emitted('saved')).toHaveLength(1)
    })

    it('disables Save for an included RAW_ARTIFACTS with no type and says why', async () => {
        const w = await mountWith()
        await pickMode(w, 'OVERRIDE')
        await select(w, 'package-selection-RAW_ARTIFACTS').vm.$emit('update:value', 'INCLUDE')
        expect(w.find('[data-testid="package-form-error"]').text()).toBe('RAW_ARTIFACTS needs at least one artifact type while included')
        expect(w.find('[data-testid="package-save"]').attributes('disabled')).toBeDefined()
        await select(w, 'package-artifact-types-RAW_ARTIFACTS').vm.$emit('update:value', ['USER_DOCUMENT'])
        expect(w.find('[data-testid="package-form-error"]').exists()).toBe(false)
        expect(w.find('[data-testid="package-save"]').attributes('disabled')).toBeUndefined()
    })

    it('INHERIT over a stored row asks "Inherit again?", then deletes without a second confirmation and reloads', async () => {
        views.FDA_PREMARKET = viewOf(FDA, { stored: { uuid: 's1', mode: 'OVERRIDE', enabled: false, sbomProfile: 'mine', parts: [] },
            parent: { ...resolution, settings: { ...resolution.settings, enabled: true } } })
        const w = await mountWith()
        expect(checkedMode(w)).toBe('OVERRIDE')
        expect(w.find('[data-testid="package-remove"]').exists()).toBe(false)
        await pickMode(w, 'INHERIT')
        expect(enabledSwitch(w).props('value')).toBe(true)
        expect(enabledSwitch(w).props('disabled')).toBe(true)
        expect(select(w, 'sbom-profile-picker').props('value')).toBe('default')
        expect(w.find('[data-testid="package-source"]').text()).toBe('Inherited from organization, revision 2: Save removes the stored settings')
        await w.find('[data-testid="package-save"]').trigger('click')
        await flushPromises()
        expect(dialogWarning).toHaveBeenCalledTimes(1)
        expect(dialogWarning.mock.calls[0][0].title).toBe('Inherit again?')
        expect(dialogWarning.mock.calls[0][0].content)
            .toBe("Remove this component's override and inherit the perspective or organization settings again?")
        expect(mutate).not.toHaveBeenCalled()

        views.FDA_PREMARKET = viewOf(FDA)
        await dialogWarning.mock.calls[0][0].onPositiveClick()
        await flushPromises()
        expect(fire).not.toHaveBeenCalled()
        expect(operation(mutate.mock.calls[0][0])).toBe('DeletePackageSettings')
        expect(mutate.mock.calls[0][0].variables).toEqual({ org: 'o1', scope: 'COMPONENT', object: 'c1', kind: 'FDA_PREMARKET' })
        expect(w.emitted('removed')).toHaveLength(1)
        expect(checkedMode(w)).toBe('INHERIT')
        expect(w.find('[data-testid="package-save"]').exists()).toBe(false)
    })

    it('a refused "Inherit again" shows as text in the object form', async () => {
        views.FDA_PREMARKET = viewOf(FDA, { stored: { uuid: 's1', mode: 'OVERRIDE', sbomProfile: 'default', parts: [] } })
        const w = await mountWith()
        await pickMode(w, 'INHERIT')
        await w.find('[data-testid="package-save"]').trigger('click')
        await flushPromises()
        const NULL_TEXT = "The field at path '/deletePackageSettings' was declared as a non null type, but the code involved in "
            + 'retrieving data has wrongly returned a null value.  The graphql specification requires that the parent '
            + 'field be set to null, or if that is non nullable that it bubble up null to its parent and so on. The '
            + "non-nullable type is 'Boolean' within parent type 'Mutation'"
        mutate.mockRejectedValueOnce(Object.assign(new Error('Not authorized\n' + NULL_TEXT), { errors: [
            { message: 'Not authorized', extensions: { classification: 'DataFetchingException' } },
            { message: NULL_TEXT, path: ['deletePackageSettings'], extensions: { classification: 'NullValueInNonNullableField' } },
        ] }))
        await dialogWarning.mock.calls[0][0].onPositiveClick()
        await flushPromises()
        expect(fire.mock.calls.at(-1)).toEqual([{ title: 'Error!', text: 'Not authorized', icon: 'error' }])
        expect(w.emitted('removed')).toBeUndefined()
    })

    it('the organization keeps no mode radio and its Remove, which confirms, then deletes', async () => {
        views.FDA_PREMARKET = viewOf(FDA, { stored: { uuid: 's1', sbomProfile: 'default', parts: [] } })
        const w = await mountWith({ scope: 'ORGANIZATION', objectUuid: null })
        expect(w.find('[data-testid="package-mode"]').exists()).toBe(false)
        expect(w.find('[data-testid="package-save"]').exists()).toBe(true)
        fire.mockResolvedValueOnce({ isConfirmed: false })
        await w.find('[data-testid="package-remove"]').trigger('click')
        await flushPromises()
        expect(fire.mock.calls[0][0].title).toBe('Remove package settings?')
        expect(mutate).not.toHaveBeenCalled()
        fire.mockResolvedValueOnce({ isConfirmed: true })
        await w.find('[data-testid="package-remove"]').trigger('click')
        await flushPromises()
        expect(operation(mutate.mock.calls[0][0])).toBe('DeletePackageSettings')
        expect(mutate.mock.calls[0][0].variables).toEqual({ org: 'o1', scope: 'ORGANIZATION', object: null, kind: 'FDA_PREMARKET' })
        expect(w.emitted('removed')).toHaveLength(1)
        expect(dialogWarning).not.toHaveBeenCalled()
    })

    it('opens in INHERIT with no row: every field and the Enabled switch read-only at the inherited value, no Save', async () => {
        views.FDA_PREMARKET = viewOf(FDA, { parent: { ...resolution, settings: { ...resolution.settings, enabled: true } } })
        const w = await mountWith()
        expect(modeValues(w)).toEqual(['INHERIT', 'OVERRIDE', 'FOLLOW_PERSPECTIVE'])
        expect(w.find('[data-testid="package-mode"]').text()).toContain('Inherit: use the perspective or organization settings')
        expect(checkedMode(w)).toBe('INHERIT')
        expect(w.find('[data-testid="package-source"]').text()).toBe('Inherited from organization, revision 2')
        expect(w.find('[data-testid="package-fields"]').attributes('data-inherited')).toBe('true')
        expect(enabledSwitch(w).props('value')).toBe(true)
        expect(enabledSwitch(w).props('disabled')).toBe(true)
        expect(select(w, 'package-selection-VEX').props('disabled')).toBe(true)
        expect(select(w, 'package-suppressed-VEX').props('disabled')).toBe(true)
        expect(select(w, 'sbom-profile-picker').props('disabled')).toBe(true)
        const description = w.findAllComponents({ name: 'Input' }).find((c: any) => c.attributes('data-testid') === 'package-description')!
        expect(merged(description)).toBe(true)
        expect(w.find('[data-testid="package-save"]').exists()).toBe(false)
        expect(w.find('[data-testid="package-remove"]').exists()).toBe(false)
        // The kind stays selectable, and so does the mode.
        expect(select(w, 'package-kind').props('disabled')).toBe(false)
        expect(radios(w).every((r: any) => !(r.element as HTMLInputElement).disabled)).toBe(true)
    })

    it('switching INHERIT to Override enables the pre-filled fields and Save; the save sends OVERRIDE, never INHERIT', async () => {
        views.FDA_PREMARKET = viewOf(FDA, { parent: { ...resolution, settings: { ...resolution.settings, enabled: true } } })
        const w = await mountWith()
        await pickMode(w, 'OVERRIDE')
        expect(w.find('[data-testid="package-fields"]').attributes('data-inherited')).toBeUndefined()
        expect(enabledSwitch(w).props('value')).toBe(true)
        expect(enabledSwitch(w).props('disabled')).toBe(false)
        expect(select(w, 'package-selection-VEX').props('disabled')).toBe(false)
        expect(w.find('[data-testid="package-source"]').text()).toBe('No override: pre-filled from organization, revision 2')
        await w.find('[data-testid="package-save"]').trigger('click')
        await flushPromises()
        expect(dialogWarning).not.toHaveBeenCalled()
        expect(operation(mutate.mock.calls[0][0])).toBe('SavePackageSettings')
        expect(mutate.mock.calls[0][0].variables.settings.mode).toBe('OVERRIDE')
        expect(mutate.mock.calls[0][0].variables.settings.enabled).toBe(true)
        expect(JSON.stringify(mutate.mock.calls[0][0].variables)).not.toContain('INHERIT')
    })

    it('a perspective offers Inherit and Override only, and asks the organization wording', async () => {
        const none = await mountWith({ scope: 'PERSPECTIVE', objectUuid: 'p2' })
        expect(modeValues(none)).toEqual(['INHERIT', 'OVERRIDE'])
        expect(none.find('[data-testid="package-mode"]').text()).toContain('Inherit: use the organization settings')
        expect(none.find('[data-testid="package-save"]').exists()).toBe(false)

        views.FDA_PREMARKET = viewOf(FDA, { stored: { uuid: 's1', sbomProfile: 'default', parts: [] } })
        const stored = await mountWith({ scope: 'PERSPECTIVE', objectUuid: 'p2' })
        expect(checkedMode(stored)).toBe('OVERRIDE')
        await pickMode(stored, 'INHERIT')
        await stored.find('[data-testid="package-save"]').trigger('click')
        await flushPromises()
        expect(dialogWarning.mock.calls[0][0].content).toBe("Remove this perspective's override and inherit the organization settings again?")
    })

    it('the source holds no Remove outside the organization, and no save sends INHERIT', () => {
        const src = readFileSync(resolve(__dirname, 'PackageSettingsEditor.vue'), 'utf8')
        expect(src).toMatch(/v-if="scope === 'ORGANIZATION' && view\.stored" data-testid="package-remove"/)
        const save = src.slice(src.indexOf('async function save ('), src.indexOf('async function remove ('))
        expect(save.indexOf('inherits.value) return')).toBeGreaterThan(-1)
        expect(save.indexOf('inherits.value) return')).toBeLessThan(save.indexOf('packageSettingsInput('))
    })

    it('shows a CONFLICT parent; CE offers Inherit and Override, Pro Follow as well', async () => {
        const conflict = { status: 'CONFLICT', source: null, sourceObject: null, settings: null,
            conflictingPerspectives: [{ uuid: 'p1', name: 'Payments' }, { uuid: 'p2', name: 'Retail' }] }
        views.FDA_PREMARKET = viewOf(FDA, { parent: conflict, effective: conflict })
        const pro = await mountWith()
        expect(pro.find('[data-testid="package-conflict"]').text()).toContain('Payments, Retail')
        expect(pro.find('[data-testid="package-mode"]').exists()).toBe(true)
        const ce = await mountWith({ installationType: 'OSS' })
        expect(modeValues(ce)).toEqual(['INHERIT', 'OVERRIDE'])
    })

    it('reloadCatalog() reads the catalog alone and leaves the form as edited', async () => {
        const w = await mountWith()
        await pickMode(w, 'OVERRIDE')
        await select(w, 'package-selection-VEX').vm.$emit('update:value', 'EXCLUDE')
        const viewReads = calls('PackageSettingsEditorView').length
        const catalogReads = calls('SbomProfileCatalog').length
        await (w.vm as any).reloadCatalog()
        await flushPromises()
        expect(calls('SbomProfileCatalog')).toHaveLength(catalogReads + 1)
        expect(calls('SbomProfileCatalog').at(-1)![0].variables).toEqual({ org: 'o1', scope: 'COMPONENT', object: 'c1' })
        expect(calls('PackageSettingsEditorView')).toHaveLength(viewReads)
        expect(select(w, 'package-selection-VEX').props('value')).toBe('EXCLUDE')
    })

    it('shows the Enabled switch first in the fields, off by default, with the kind in its help, and sends it', async () => {
        const w = await mountWith()
        const fields = w.find('[data-testid="package-fields"]')
        const html = fields.html()
        expect(html.indexOf('data-testid="package-enabled"')).toBeGreaterThan(-1)
        expect(html.indexOf('data-testid="package-enabled"')).toBeLessThan(html.indexOf('data-testid="package-sbom-profile"'))
        const sw = w.findAllComponents(NSwitch).find((c: any) => c.attributes('data-testid') === 'package-enabled')!
        expect(sw.props('value')).toBe(false)
        await pickMode(w, 'OVERRIDE')
        expect(fields.text()).toContain('Off: releases of this scope do not offer FDA premarket and no new submission can be made; '
            + 'existing submissions stay readable and can be withdrawn.')
        await sw.vm.$emit('update:value', true)
        await w.find('[data-testid="package-save"]').trigger('click')
        await flushPromises()
        expect(mutate.mock.calls[0][0].variables.settings.enabled).toBe(true)
    })

    it('pre-fills Enabled from the stored row, and a FOLLOW row sends no enabled and shows the followed state', async () => {
        views.FDA_PREMARKET = viewOf(FDA, { stored: { uuid: 'r', mode: 'OVERRIDE', enabled: true, sbomProfile: 'default', parts: [] } })
        const on = await mountWith()
        expect(on.findAllComponents(NSwitch).find((c: any) => c.attributes('data-testid') === 'package-enabled')!.props('value'))
            .toBe(true)
        on.unmount()

        views.FDA_PREMARKET = viewOf(FDA, {
            stored: { uuid: 'r', mode: 'FOLLOW_PERSPECTIVE', followedPerspective: 'p1', enabled: null },
            effective: { ...resolution, source: 'FOLLOWED_PERSPECTIVE', sourceObject: 'p1',
                settings: { ...resolution.settings, enabled: true } },
        })
        const follow = await mountWith({ perspectiveOptions: [{ label: 'Payments', value: 'p1' }] })
        expect(follow.find('[data-testid="package-enabled"]').exists()).toBe(false)
        expect(follow.find('[data-testid="package-effective-enabled"]').text()).toBe('Enabled here: on')
        await follow.find('[data-testid="package-save"]').trigger('click')
        await flushPromises()
        expect(mutate.mock.calls[0][0].variables.settings).toEqual({ mode: 'FOLLOW_PERSPECTIVE', followedPerspective: 'p1' })
    })

    it('a read-only editor shows the switch disabled at the stored value', async () => {
        views.FDA_PREMARKET = viewOf(FDA, { stored: { uuid: 'r', mode: 'OVERRIDE', enabled: true, sbomProfile: 'default', parts: [] } })
        const w = await mountWith({ isWritable: false })
        const sw = w.findAllComponents(NSwitch).find((c: any) => c.attributes('data-testid') === 'package-enabled')!
        expect(sw.props('value')).toBe(true)
        expect(sw.props('disabled')).toBe(true)
    })

    it('the Enabled help follows the switch: Off text when off, On text once switched on', async () => {
        views.FDA_PREMARKET = viewOf(FDA, { stored: { uuid: 'r', mode: 'OVERRIDE', enabled: false, sbomProfile: 'default', parts: [] } })
        const w = await mountWith()
        const help = () => w.find('.package-enabled-help').text()
        expect(help()).toBe('Off: releases of this scope do not offer FDA premarket and no new submission can be made; '
            + 'existing submissions stay readable and can be withdrawn.')
        await enabledSwitch(w).vm.$emit('update:value', true)
        await flushPromises()
        expect(help()).toBe('On: releases of this scope offer FDA premarket and can be submitted.')
    })

    it('the Enabled help describes the shown value when read-only: an inherited on, a reader on a stored on', async () => {
        views.FDA_PREMARKET = viewOf(FDA, { parent: { ...resolution, settings: { ...resolution.settings, enabled: true } } })
        const inherit = await mountWith()
        expect(checkedMode(inherit)).toBe('INHERIT')
        expect(inherit.find('.package-enabled-help').text()).toBe('On: releases of this scope offer FDA premarket and can be submitted.')
        // Override pre-filled from the parent: switched off there, the text follows.
        await pickMode(inherit, 'OVERRIDE')
        await enabledSwitch(inherit).vm.$emit('update:value', false)
        await flushPromises()
        expect(inherit.find('.package-enabled-help').text()).toContain('Off: releases of this scope do not offer FDA premarket')
        inherit.unmount()

        views.FDA_PREMARKET = viewOf(FDA, { stored: { uuid: 'r', mode: 'OVERRIDE', enabled: true, sbomProfile: 'default', parts: [] } })
        const reader = await mountWith({ isWritable: false })
        expect(reader.find('.package-enabled-help').text()).toBe('On: releases of this scope offer FDA premarket and can be submitted.')
    })

    it('INHERIT at COMPONENT scope with perspectives shows the conflict hint under the source line, naming the kind', async () => {
        const w = await mountWith({ perspectiveOptions: [{ label: 'Payments', value: 'p1' }, { label: 'Retail', value: 'p2' }] })
        expect(checkedMode(w)).toBe('INHERIT')
        const hint = w.find('[data-testid="package-inherit-hint"]')
        expect(hint.text()).toBe("Inheriting resolves through this component's perspectives: if more than one of them carries its own "
            + 'FDA premarket settings, inheriting stops with a conflict \u2014 use Follow one of its perspectives to pin one.')
        expect(hint.classes()).toContain('text-muted')
        const html = w.html()
        expect(html.indexOf('data-testid="package-source"')).toBeLessThan(html.indexOf('data-testid="package-inherit-hint"'))
        await pickMode(w, 'OVERRIDE')
        expect(w.find('[data-testid="package-inherit-hint"]').exists()).toBe(false)
        await pickMode(w, 'FOLLOW_PERSPECTIVE')
        expect(w.find('[data-testid="package-inherit-hint"]').exists()).toBe(false)
    })

    it('no conflict hint without perspectives, at a perspective, or once the resolution conflicts (its own line stays)', async () => {
        const none = await mountWith()
        expect(checkedMode(none)).toBe('INHERIT')
        expect(none.find('[data-testid="package-inherit-hint"]').exists()).toBe(false)
        none.unmount()
        const perspective = await mountWith({ scope: 'PERSPECTIVE', objectUuid: 'p1',
            perspectiveOptions: [{ label: 'Payments', value: 'p1' }] })
        expect(perspective.find('[data-testid="package-inherit-hint"]').exists()).toBe(false)
        perspective.unmount()
        const conflict = { status: 'CONFLICT', source: null, sourceObject: null, settings: null,
            conflictingPerspectives: [{ uuid: 'p1', name: 'Payments' }, { uuid: 'p2', name: 'Retail' }] }
        views.FDA_PREMARKET = viewOf(FDA, { parent: conflict, effective: conflict })
        const conflicted = await mountWith({ perspectiveOptions: [{ label: 'Payments', value: 'p1' }, { label: 'Retail', value: 'p2' }] })
        expect(conflicted.find('[data-testid="package-inherit-hint"]').exists()).toBe(false)
        expect(conflicted.find('[data-testid="package-conflict"]').exists()).toBe(true)
    })
})
