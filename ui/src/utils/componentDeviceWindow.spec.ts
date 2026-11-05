import { describe, it, expect } from 'vitest'
import { loadComponentDeviceWindow, deviceWindowMutationInput, deviceSaveInput,
    deviceWindowWarningsOf, COMPONENT_DEVICE_WINDOW_QUERY } from './componentDeviceWindow'
import { withoutAdditiveFields } from './graphqlDriftFallback'
import { print, type DocumentNode } from 'graphql'

// A REAL drift error, shaped the way graphql-java actually returns one, rather than a fake
// predicate the module was handed. isSchemaDriftError is imported by the module now, so this
// spec exercises the same classification the running app does -- a fake predicate would have
// gone on passing even if the real one stopped recognising a validation error.
const driftError = () => Object.assign(new Error('Validation error'), {
    graphQLErrors: [{ message: "Validation error (FieldUndefined@[component/medicalProfile/"
        + "deviceSupportWindow]) : Field 'deviceSupportWindow' in type 'MedicalProfile' is"
        + " undefined", extensions: { classification: 'ValidationError' } }]
})

function client (result: any, throws?: any) {
    return { query: async () => { if (throws) throw throws; return result } }
}

describe('reading the declared window', () => {
    it('reads the component-level window', async () => {
        const r = await loadComponentDeviceWindow(client({
            data: { component: { uuid: 'c1', medicalProfile: {
                deviceSupportWindow: { eos: '2031-01-31', eol: null, assertedBy: 'u1', assessedAt: 'i' } } } }
        }), 'c1')
        expect(r.supported).toBe(true)
        expect(r.window).toEqual({ eos: '2031-01-31', eol: null })
        expect(r.assertedBy).toBe('u1')
    })

    it('reports not-declared when the component declares nothing', async () => {
        const r = await loadComponentDeviceWindow(client({
            data: { component: { uuid: 'c1', medicalProfile: null } } }), 'c1')
        expect(r.supported).toBe(true)
        expect(r.window).toEqual({ eos: null, eol: null })
    })

    /**
     * CE declares Component.medicalProfile WITHOUT deviceSupportWindow, so this document is a
     * VALIDATION ERROR there -- not a null field. "This build cannot show the window" and "this
     * device has no window declared" are different facts, and collapsing them would put an
     * editable, always-empty panel in front of a CE operator whose saves would all fail.
     */
    it('reports unsupported, not not-declared, on a schema-drift error', async () => {
        const r = await loadComponentDeviceWindow(client(null, driftError()), 'c1')
        expect(r.supported).toBe(false)
        expect(r.window).toEqual({ eos: null, eol: null })
    })

    /** A real failure must not be swallowed as "unsupported" -- that would hide an outage. */
    it('rethrows a non-drift error', async () => {
        await expect(loadComponentDeviceWindow(client(null, new Error('boom')), 'c1'))
            .rejects.toThrow('boom')
    })

    // FDAW-3, B3: the derived warning rides the full document.
    const EOS_BEFORE_EOL = { code: 'EOS_BEFORE_EOL', message: 'End of support (2030-12-31, last security update) is before end of life (2033-12-31, last sale date)' }

    it('surfaces the warnings the full document returns', async () => {
        const r = await loadComponentDeviceWindow(client({
            data: { component: { uuid: 'c1', medicalProfile: { deviceSupportWindow: {
                eos: '2030-12-31', eol: '2033-12-31', warnings: [EOS_BEFORE_EOL] } } } }
        }), 'c1')
        expect(r.supported).toBe(true)
        expect(r.warnings).toEqual([EOS_BEFORE_EOL])
    })

    it('reports no warnings when the window has none', async () => {
        const r = await loadComponentDeviceWindow(client({
            data: { component: { uuid: 'c1', medicalProfile: { deviceSupportWindow: {
                eos: '2034-06-30', eol: '2033-12-31', warnings: [] } } } }
        }), 'c1')
        expect(r.warnings).toEqual([])
    })

    /** A backend with the window but not the warning: the core document answers, no warning. */
    it('falls back to the core document on a drift refusal of the full one', async () => {
        const core = print(withoutAdditiveFields(COMPONENT_DEVICE_WINDOW_QUERY))
        const sent: string[] = []
        const r = await loadComponentDeviceWindow({
            query: async ({ query }: { query: DocumentNode }) => {
                sent.push(print(query))
                if (print(query) !== core) throw driftError()
                return { data: { component: { uuid: 'c1', medicalProfile: {
                    deviceSupportWindow: { eos: '2030-12-31', eol: '2033-12-31' } } } } }
            }
        }, 'c1')
        expect(sent).toHaveLength(2)
        expect(r.supported).toBe(true)
        expect(r.window).toEqual({ eos: '2030-12-31', eol: '2033-12-31' })
        expect(r.warnings).toEqual([])
    })

    it('reports unsupported only when the core document is refused too', async () => {
        let calls = 0
        const r = await loadComponentDeviceWindow({
            query: async () => { calls++; throw driftError() }
        }, 'c1')
        expect(calls).toBe(2)
        expect(r.supported).toBe(false)
        expect(r.warnings).toEqual([])
    })
})

/**
 * FDAW-3, B3: `deviceWindowWarningsOf` normalises whatever the server (or a stubbed client in a
 * test) hands it. A malformed `warnings` that is present but not an array -- not just absent or
 * null -- must still come back as an empty list rather than throwing out of `.filter`, since a
 * throw here would break the window read for a reason with nothing to do with the dates.
 */
describe('deviceWindowWarningsOf: normalising whatever the server sent', () => {
    it('returns empty for an absent, null or non-array warnings value, without throwing', () => {
        expect(deviceWindowWarningsOf(undefined)).toEqual([])
        expect(deviceWindowWarningsOf({})).toEqual([])
        expect(deviceWindowWarningsOf({ warnings: null })).toEqual([])
        expect(() => deviceWindowWarningsOf({ warnings: 'oops' })).not.toThrow()
        expect(deviceWindowWarningsOf({ warnings: 'oops' })).toEqual([])
        expect(deviceWindowWarningsOf({ warnings: {} })).toEqual([])
    })

    it('drops an entry with no string message and keeps the rest, stringifying code', () => {
        const warnings = [
            { code: 'EOS_BEFORE_EOL', message: 'ok' },
            { code: 'X', message: null },
            null,
            { message: 'no code' }
        ]
        expect(deviceWindowWarningsOf({ warnings })).toEqual([
            { code: 'EOS_BEFORE_EOL', message: 'ok' },
            { code: '', message: 'no code' }
        ])
    })
})

describe('writing the window', () => {
    const none = { eos: null, eol: null }

    it('sends the dates when they change', () => {
        expect(deviceWindowMutationInput('c1', 'Pump', { eos: '2031-01-31', eol: null }, none))
            .toEqual({ uuid: 'c1', name: 'Pump', deviceSupportWindow: { eos: '2031-01-31', eol: null } })
    })

    /**
     * Blanking both pickers is a RETRACTION and must send the flag. An empty window object is
     * deliberately not a clear on the server, so sending one would leave the old commitment
     * silently in force while the screen showed it gone.
     */
    it('sends the clear flag when both dates are emptied', () => {
        expect(deviceWindowMutationInput('c1', 'Pump', none, { eos: '2031-01-31', eol: null }))
            .toEqual({ uuid: 'c1', name: 'Pump', clearDeviceSupportWindow: true })
    })

    /** Nothing declared and nothing entered is not a retraction of anything. */
    it('sends nothing when there was nothing to clear', () => {
        expect(deviceWindowMutationInput('c1', 'Pump', none, none)).toBeNull()
    })

    /**
     * An unrelated save must not rewrite the window: the server stamps assertedBy/assessedAt on
     * every write, so a no-op resend would re-attribute a claim nobody touched.
     */
    it('sends nothing when the dates are unchanged', () => {
        const w = { eos: '2031-01-31', eol: '2033-06-30' }
        expect(deviceWindowMutationInput('c1', 'Pump', { ...w }, { ...w })).toBeNull()
    })
})

/** FDAW-3, B8: the Device tab's one Save sends the class and the window in one updateComponent. */
describe('the Device tab save input', () => {
    const none = { eos: null, eol: null }
    const stored = { eos: '2031-01-31', eol: '2033-06-30' }

    it('sends the class alone when only the class changed', () => {
        expect(deviceSaveInput('c1', 'Pump', 'MEDICAL_TRACKED', 'NONE', none, none))
            .toEqual({ uuid: 'c1', name: 'Pump', deviceClass: 'MEDICAL_TRACKED' })
    })

    it('sends the window alone when only the dates changed', () => {
        expect(deviceSaveInput('c1', 'Pump', 'MEDICAL_TRACKED', 'MEDICAL_TRACKED',
            { eos: '2034-06-30', eol: '2033-12-31' }, none))
            .toEqual({ uuid: 'c1', name: 'Pump', deviceSupportWindow: { eos: '2034-06-30', eol: '2033-12-31' } })
    })

    it('sends both, class first, when a class is picked with its dates', () => {
        const input = deviceSaveInput('c1', 'Pump', 'MEDICAL_UNTRACKED', 'NONE',
            { eos: '2034-06-30', eol: '2033-12-31' }, none)
        expect(input).toEqual({ uuid: 'c1', name: 'Pump', deviceClass: 'MEDICAL_UNTRACKED',
            deviceSupportWindow: { eos: '2034-06-30', eol: '2033-12-31' } })
        const keys = Object.keys(input as object)
        expect(keys.indexOf('deviceClass')).toBeLessThan(keys.indexOf('deviceSupportWindow'))
    })

    /** NONE retracts the window server-side; a window beside it would be refused as a non-device's. */
    it('sends the class NONE and no window part, even with changed dates', () => {
        const input = deviceSaveInput('c1', 'Pump', 'NONE', 'MEDICAL_TRACKED', { eos: '2040-01-01', eol: null }, stored)
        expect(input).toEqual({ uuid: 'c1', name: 'Pump', deviceClass: 'NONE' })
        expect(input).not.toHaveProperty('deviceSupportWindow')
        expect(input).not.toHaveProperty('clearDeviceSupportWindow')
    })

    it('sends the clear flag when both dates are blanked over a stored window', () => {
        expect(deviceSaveInput('c1', 'Pump', 'MEDICAL_TRACKED', 'MEDICAL_TRACKED', none, stored))
            .toEqual({ uuid: 'c1', name: 'Pump', clearDeviceSupportWindow: true })
    })

    it('returns null when nothing changed', () => {
        expect(deviceSaveInput('c1', 'Pump', 'MEDICAL_TRACKED', 'MEDICAL_TRACKED', stored, { ...stored })).toBeNull()
        expect(deviceSaveInput('c1', 'Pump', 'NONE', 'NONE', none, none)).toBeNull()
    })

    it('always carries the name, which UpdateComponentInput requires', () => {
        for (const input of [
            deviceSaveInput('c1', 'Pump', 'MEDICAL_TRACKED', 'NONE', none, none),
            deviceSaveInput('c1', 'Pump', 'MEDICAL_TRACKED', 'MEDICAL_TRACKED', none, stored),
            deviceSaveInput('c1', 'Pump', 'NONE', 'MEDICAL_TRACKED', none, stored)
        ]) {
            expect(input).toHaveProperty('name', 'Pump')
            expect(input).toHaveProperty('uuid', 'c1')
        }
    })
})

/**
 * UpdateComponentInput.name is String!, so a {uuid, window} partial is rejected at variable
 * COERCION -- before the resolver, with no server-side log explaining it. The panel's Save then
 * fails every single time. Layer 2 caught this; nothing here did, because every assertion
 * checked the fields I remembered to send rather than the ones the schema demands.
 */
describe('the mutation input satisfies the required fields', () => {
    const none = { eos: null, eol: null }

    it('always carries name on a set', () => {
        expect(deviceWindowMutationInput('c1', 'Pump', { eos: '2031-01-31', eol: null }, none))
            .toHaveProperty('name', 'Pump')
    })

    it('always carries name on a clear', () => {
        expect(deviceWindowMutationInput('c1', 'Pump', none, { eos: '2031-01-31', eol: null }))
            .toHaveProperty('name', 'Pump')
    })
})

describe('the document itself', () => {
    /** It must not be folded into COMPONENT_FULL_DATA -- see the module javadoc. */
    it('is a standalone query, not part of the mutation response', () => {
        const text = print(COMPONENT_DEVICE_WINDOW_QUERY)
        expect(text).toMatch(/query componentDeviceWindow/)
        expect(text).toMatch(/deviceSupportWindow/)
    })
})
