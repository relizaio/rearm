import { describe, it, expect, vi } from 'vitest'
import { exportWithMetadataFallback } from './exportMetadataFallback'

const drift = (e: any) => e?.drift === true
const ok = (v: any) => () => Promise.resolve(v)
const boom = (e: any) => () => Promise.reject(e)

describe('sending the metadata flags to a backend that may not take them', () => {
    it('uses the full document and reports nothing when the server accepts it', async () => {
        const core = vi.fn()
        const r = await exportWithMetadataFallback({
            runFull: ok('FULL'), runCore: core, flagsUnsupported: false, isDriftError: drift })
        expect(r).toEqual({ data: 'FULL', usedCore: false, justDiscovered: false })
        expect(core).not.toHaveBeenCalled()
    })

    it('retries without the flags on a schema-drift rejection, and says it discovered that', async () => {
        const r = await exportWithMetadataFallback({
            runFull: boom({ drift: true }), runCore: ok('CORE'),
            flagsUnsupported: false, isDriftError: drift })
        expect(r).toEqual({ data: 'CORE', usedCore: true, justDiscovered: true })
    })

    // THE HIGHEST-VALUE CASE. The server refuses includeSupportMetadata=true on an org that has
    // the setting off, deliberately, because a document that silently came back without the
    // disclosure reads exactly like one where nothing was attested. Retrying that WITHOUT the
    // flag would produce precisely that document -- the refusal defeated by its own client.
    it('never retries a refusal that is not schema drift', async () => {
        const refusal = { drift: false, message: 'organization setting is DISABLED' }
        const core = vi.fn()
        await expect(exportWithMetadataFallback({
            runFull: boom(refusal), runCore: core,
            flagsUnsupported: false, isDriftError: drift })).rejects.toBe(refusal)
        expect(core).not.toHaveBeenCalled()
    })

    // The mutation also carries two ENUM arguments, which drift the same way. Latching on the
    // rejection alone would turn one unrelated bad request into flagless exports forever.
    it('does not claim discovery when the flagless retry fails too', async () => {
        const second = { drift: true, message: 'unknown enum value' }
        await expect(exportWithMetadataFallback({
            runFull: boom({ drift: true }), runCore: boom(second),
            flagsUnsupported: false, isDriftError: drift })).rejects.toBe(second)
    })

    // Once latched, every later export is flagless -- and must NOT re-announce it. Saying it
    // again on each export is noise; the form has to show it instead.
    it('goes straight to the flagless document once latched, silently', async () => {
        const full = vi.fn()
        const r = await exportWithMetadataFallback({
            runFull: full, runCore: ok('CORE'), flagsUnsupported: true, isDriftError: drift })
        expect(r).toEqual({ data: 'CORE', usedCore: true, justDiscovered: false })
        expect(full).not.toHaveBeenCalled()
    })
})
