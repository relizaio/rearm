import { describe, it, expect } from 'vitest'
import { ref } from 'vue'
import { useFileComponentsSwitch } from './useFileComponentsSwitch'
import { FileSwitchUnsupportedError } from './exportMetadataFallback'
import { SBOM_EXPORT_CORE, SBOM_EXPORT_WITH_FILE_SWITCH, SBOM_EXPORT_WITH_METADATA_FLAGS } from './releaseSbomExportDocuments'

/**
 * The Export Release BOM dialog's "Leave out file components" switch (SCORE-11), run: which
 * export document is sent with the switch on and off and with what, that a server which cannot
 * take the switch never gets the switchless document instead, and what that refusal does to the
 * switch. ReleaseView is not mounted in tests; it delegates all of this to the composable.
 */
const baseVariables = { release: 'r-1', tldOnly: true, ignoreDev: false, structure: 'FLAT', belongsTo: null, mediaType: 'JSON', excludeCoverageTypes: null }
const fullVariables = { ...baseVariables, includeSupportMetadata: null, includeInternalMetadata: false }
const drift = (e: any) => e?.drift === true

interface Sent { mutation: any, variables: Record<string, any> }

/** A server that answers each document with `answer(document)`, recording what was sent. */
function server (answer: (mutation: any) => Promise<any>) {
    const sent: Sent[] = []
    const run = (mutation: any, variables: Record<string, any>) => {
        sent.push({ mutation, variables })
        return answer(mutation)
    }
    return { sent, run }
}

const accepting = () => server(() => Promise.resolve({ data: { releaseSbomExport: '{}' } }))
/** A server without the switch argument: the switch document fails validation. */
const withoutTheSwitch = () => server(m => m === SBOM_EXPORT_WITH_FILE_SWITCH
    ? Promise.reject({ drift: true, message: 'Unknown argument excludeFileComponents' })
    : Promise.resolve({ data: { releaseSbomExport: '{}' } }))
/** A server without the per-export metadata arguments either. */
const withoutMetadataArgs = () => server(m => m === SBOM_EXPORT_CORE
    ? Promise.resolve({ data: { releaseSbomExport: '{}' } })
    : Promise.reject({ drift: true, message: 'Unknown argument includeSupportMetadata' }))

function exportWith (sw: ReturnType<typeof useFileComponentsSwitch>, s: ReturnType<typeof server>) {
    return sw.exportReleaseSbom({ run: s.run, baseVariables, fullVariables, isDriftError: drift })
}

describe('useFileComponentsSwitch: the export the switch sends', () => {
    it('with the switch on, sends the switch document carrying excludeFileComponents true, once', async () => {
        const sw = useFileComponentsSwitch(ref(false))
        sw.excludeFileComponents.value = true
        const s = accepting()

        const r = await exportWith(sw, s)

        expect(s.sent).toEqual([{ mutation: SBOM_EXPORT_WITH_FILE_SWITCH, variables: { ...fullVariables, excludeFileComponents: true } }])
        expect(r.usedCore).toBe(false)
    })

    it('with the switch off, sends the metadata-flags document exactly as before the switch existed', async () => {
        const sw = useFileComponentsSwitch(ref(false))
        const s = accepting()

        await exportWith(sw, s)

        expect(s.sent).toEqual([{ mutation: SBOM_EXPORT_WITH_METADATA_FLAGS, variables: fullVariables }])
        expect('excludeFileComponents' in s.sent[0].variables).toBe(false)
    })

    it('with the switch on, a server without the switch is never sent the document that keeps the files', async () => {
        const sw = useFileComponentsSwitch(ref(false))
        sw.excludeFileComponents.value = true
        const s = withoutTheSwitch()

        const err = await exportWith(sw, s).catch(e => e)

        expect(err).toBeInstanceOf(FileSwitchUnsupportedError)
        expect(s.sent.map(x => x.mutation)).toEqual([SBOM_EXPORT_WITH_FILE_SWITCH])
    })

    it('with the switch off, a server without the metadata arguments still gets the core document once', async () => {
        const sw = useFileComponentsSwitch(ref(false))
        const s = withoutMetadataArgs()

        const r = await exportWith(sw, s)

        expect(s.sent).toEqual([
            { mutation: SBOM_EXPORT_WITH_METADATA_FLAGS, variables: fullVariables },
            { mutation: SBOM_EXPORT_CORE, variables: baseVariables }
        ])
        expect(r).toMatchObject({ usedCore: true, justDiscovered: true })
    })
})

describe('useFileComponentsSwitch: a refusal and a reopening', () => {
    it('latches a refusal, turns the switch off and disables it; later exports are switch-off ones', async () => {
        const sw = useFileComponentsSwitch(ref(false))
        sw.excludeFileComponents.value = true
        const s = withoutTheSwitch()

        const err = await exportWith(sw, s).catch(e => e)
        expect(sw.latchRefusal(err)).toBe(true)

        expect(sw.exportFileSwitchUnsupported.value).toBe(true)
        expect(sw.excludeFileComponents.value).toBe(false)
        expect(sw.exportFileSwitchAvailable.value).toBe(false)
        await exportWith(sw, s)
        expect(s.sent.map(x => x.mutation)).toEqual([SBOM_EXPORT_WITH_FILE_SWITCH, SBOM_EXPORT_WITH_METADATA_FLAGS])
    })

    it('leaves any other failure to the caller and the switch as it was', () => {
        const sw = useFileComponentsSwitch(ref(false))
        sw.excludeFileComponents.value = true

        expect(sw.latchRefusal(new Error('Not authorized'))).toBe(false)
        expect(sw.excludeFileComponents.value).toBe(true)
        expect(sw.exportFileSwitchAvailable.value).toBe(true)
    })

    it('starts every opening with the switch off, and keeps the latch', () => {
        const sw = useFileComponentsSwitch(ref(false))
        sw.latchRefusal(new FileSwitchUnsupportedError())
        sw.excludeFileComponents.value = true

        sw.reset()

        expect(sw.excludeFileComponents.value).toBe(false)
        expect(sw.exportFileSwitchAvailable.value).toBe(false)
    })
})
