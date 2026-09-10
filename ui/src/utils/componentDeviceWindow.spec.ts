import { describe, it, expect } from 'vitest'
import { loadComponentDeviceWindow, deviceWindowMutationInput,
    COMPONENT_DEVICE_WINDOW_QUERY } from './componentDeviceWindow'
import { print } from 'graphql'

const drift = (e: any) => e?.driftLike === true

function client (result: any, throws?: any) {
    return { query: async () => { if (throws) throw throws; return result } }
}

describe('reading the declared window', () => {
    it('reads the component-level window', async () => {
        const r = await loadComponentDeviceWindow(client({
            data: { component: { uuid: 'c1', medicalProfile: {
                deviceSupportWindow: { eos: '2031-01-31', eol: null, assertedBy: 'u1', assessedAt: 'i' } } } }
        }), 'c1', drift)
        expect(r.supported).toBe(true)
        expect(r.window).toEqual({ eos: '2031-01-31', eol: null })
        expect(r.assertedBy).toBe('u1')
    })

    it('reports not-declared when the component declares nothing', async () => {
        const r = await loadComponentDeviceWindow(client({
            data: { component: { uuid: 'c1', medicalProfile: null } } }), 'c1', drift)
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
        const r = await loadComponentDeviceWindow(client(null, { driftLike: true }), 'c1', drift)
        expect(r.supported).toBe(false)
        expect(r.window).toEqual({ eos: null, eol: null })
    })

    /** A real failure must not be swallowed as "unsupported" -- that would hide an outage. */
    it('rethrows a non-drift error', async () => {
        await expect(loadComponentDeviceWindow(client(null, new Error('boom')), 'c1', drift))
            .rejects.toThrow('boom')
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
