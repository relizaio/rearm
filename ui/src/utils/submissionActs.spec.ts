// The submission acts (task TEA-30, design 4.6 row 34): the preview first, a refused preview shown as
// text in the object form with nothing sent, the confirm with its reason textarea, the reason rules,
// expectedLatestVersion, the error and gateway texts with a reload after each, and one act at a time.
// Every dialog reaches Swal as exactly one object argument, with no html key and a constant title.
import { beforeEach, describe, expect, it, vi } from 'vitest'

const fire = vi.fn()
vi.mock('sweetalert2', () => ({ default: { fire: (...a: any[]) => fire(...a) } }))
vi.mock('./fetchClient', () => ({ fetchWithAuth: vi.fn(), fetchArrayBufferWithAuth: vi.fn() }))

const { useSubmissionActs } = await import('./submissionActs')
const { TEA_ACT_GATEWAY_TEXT } = await import('./teaPublication')

const operation = (opts: any) => (opts.query ?? opts.mutation)?.definitions?.[0]?.name?.value
const NAMES = { componentName: '<img src=x onerror=alert(1)>', releaseVersion: '1.2.0' }
const HEAD = { uuid: 's1', release: 'r1', kind: 'FDA_PREMARKET', state: 'FINAL', latestVersion: 2 }
const PREVIEW = { canSubmit: true, refusal: null, message: null, nextVersion: 1, head: null,
    settings: { row: null, revision: 0, source: 'DEFAULT' }, sbomProfile: { name: 'default', row: null, revision: 0, source: 'DEFAULT' },
    plan: [{ part: 'AGGREGATE_SBOM', requirement: 'REQUIRED', selection: 'INCLUDE', generatorPresent: true }] }
const NULL_TEXT = "The field at path '/resubmitSubmission' was declared as a non null type, but the code involved in "
    + 'retrieving data has wrongly returned a null value.  The graphql specification requires that the parent '
    + 'field be set to null, or if that is non nullable that it bubble up null to its parent and so on. The '
    + "non-nullable type is 'SubmissionActResult' within parent type 'Mutation'"
const TITLES = ['Error!', 'Cannot create this submission', 'Cannot resubmit this submission', 'Create submission?', 'Resubmit?',
    'Cancel submission?', 'Submission']

let preview: any = PREVIEW
const query = vi.fn(async (opts: any) => {
    if (operation(opts) !== 'SubmissionPreview') throw new Error('unexpected query')
    return { data: { submissionPreview: preview } }
})
const mutate = vi.fn()
const reload = vi.fn()

function acts () {
    return useSubmissionActs({ client: { query, mutate }, reload })
}

function everyCallIsOneObject () {
    expect(fire.mock.calls.every(c => c.length === 1 && typeof c[0] === 'object' && c[0] !== null)).toBe(true)
    for (const c of fire.mock.calls) {
        expect(c[0]).not.toHaveProperty('html')
        expect(TITLES).toContain(c[0].title)
    }
}

describe('useSubmissionActs', () => {
    beforeEach(() => {
        fire.mockReset()
        query.mockClear()
        mutate.mockReset()
        reload.mockReset()
        preview = PREVIEW
        mutate.mockResolvedValue({ data: {
            createSubmission: { outcome: 'CREATED', version: { version: 1 } },
            resubmitSubmission: { outcome: 'RESUBMITTED', version: { version: 3 } },
            withdrawSubmission: { outcome: 'CANCELLED', version: null },
        } })
    })

    it('a refused preview shows its message as text and sends nothing', async () => {
        const message = 'Release <img src=x onerror=alert(1)> 1.2.0: the package settings conflict between perspectives '
            + '<img src=x onerror=alert(1)> and Payments'
        preview = { ...PREVIEW, canSubmit: false, refusal: 'SETTINGS_CONFLICT', message }
        await acts().create('r1', 'FDA_PREMARKET', NAMES)
        expect(operation(query.mock.calls[0][0])).toBe('SubmissionPreview')
        expect(query.mock.calls[0][0].variables).toEqual({ release: 'r1', kind: 'FDA_PREMARKET' })
        expect(fire).toHaveBeenCalledTimes(1)
        expect(fire.mock.calls[0]).toEqual([{ title: 'Cannot create this submission', text: message, icon: 'error' }])
        expect(mutate).not.toHaveBeenCalled()
        everyCallIsOneObject()
    })

    it('a refused preview on resubmit shows its message under the constant title as text and sends nothing', async () => {
        const message = 'Release <img src=x onerror=alert(1)> 1.2.0: the SBOM profile top does not resolve at component '
            + '<img src=x onerror=alert(1)>'
        preview = { ...PREVIEW, canSubmit: false, refusal: 'PROFILE_NOT_FOUND', message, head: HEAD }
        await acts().resubmit(HEAD, NAMES)
        expect(query).toHaveBeenCalledTimes(1)
        expect(operation(query.mock.calls[0][0])).toBe('SubmissionPreview')
        expect(query.mock.calls[0][0].variables).toEqual({ release: 'r1', kind: 'FDA_PREMARKET' })
        expect(fire).toHaveBeenCalledTimes(1)
        expect(fire.mock.calls[0]).toEqual([{ title: 'Cannot resubmit this submission', text: message, icon: 'error' }])
        expect(mutate).not.toHaveBeenCalled()
        everyCallIsOneObject()
    })

    // FDAW-4 (B18, T8): a KIND_DISABLED preview is explained with the client sentence, and the page re-reads.
    it('a KIND_DISABLED preview on create shows the client sentence, not the server text, and reloads', async () => {
        preview = { ...PREVIEW, canSubmit: false, refusal: 'KIND_DISABLED', message: 'Release widget 1.2.0: server text' }
        await acts().create('r1', 'FDA_PREMARKET', { ...NAMES, componentType: 'PRODUCT' })
        expect(fire.mock.calls).toEqual([[{ title: 'Cannot create this submission',
            text: 'FDA premarket is switched off for this product; switch it on under Settings > Regulatory and try again.',
            icon: 'error' }]])
        expect(reload).toHaveBeenCalledTimes(1)
        expect(mutate).not.toHaveBeenCalled()
        everyCallIsOneObject()
    })

    it('a KIND_DISABLED preview on resubmit words the head\'s component type and reloads', async () => {
        preview = { ...PREVIEW, canSubmit: false, refusal: 'KIND_DISABLED', message: 'server text', head: HEAD }
        await acts().resubmit({ ...HEAD, componentType: 'COMPONENT' }, NAMES)
        expect(fire.mock.calls).toEqual([[{ title: 'Cannot resubmit this submission',
            text: 'FDA premarket is switched off for this component; switch it on under Settings > Regulatory and try again.',
            icon: 'error' }]])
        expect(reload).toHaveBeenCalledTimes(1)
        expect(mutate).not.toHaveBeenCalled()
        everyCallIsOneObject()
    })

    it('any other refused preview keeps the server message and does not reload', async () => {
        preview = { ...PREVIEW, canSubmit: false, refusal: 'LIFECYCLE_TOO_LOW', message: 'server text' }
        await acts().create('r1', 'FDA_PREMARKET', { ...NAMES, componentType: 'PRODUCT' })
        await acts().resubmit(HEAD, NAMES)
        expect(fire.mock.calls.map(c => c[0].text)).toEqual(['server text', 'server text'])
        expect(reload).not.toHaveBeenCalled()
    })

    it('create confirms with an optional reason; cancel sends nothing; a blank reason is null; reload after', async () => {
        fire.mockResolvedValueOnce({ isConfirmed: false })
        await acts().create('r1', 'FDA_PREMARKET', NAMES)
        const confirm = fire.mock.calls[0][0]
        expect(confirm.title).toBe('Create submission?')
        expect(confirm.input).toBe('textarea')
        expect(confirm.inputLabel).toBe('Reason (optional)')
        expect(confirm.text).toContain('<img src=x onerror=alert(1)> 1.2.0')
        expect(confirm.inputValidator('')).toBeNull()
        expect(mutate).not.toHaveBeenCalled()

        fire.mockResolvedValueOnce({ isConfirmed: true, value: '   ' })
        await acts().create('r1', 'FDA_PREMARKET', NAMES)
        expect(operation(mutate.mock.calls[0][0])).toBe('CreateSubmission')
        expect(mutate.mock.calls[0][0].variables).toEqual({ release: 'r1', kind: 'FDA_PREMARKET', reason: null })
        expect(fire.mock.calls.at(-1)![0]).toEqual({ title: 'Submission', text: 'Created v1 (Created).', icon: 'success' })
        expect(reload).toHaveBeenCalledTimes(1)

        fire.mockResolvedValueOnce({ isConfirmed: true, value: 'first filing' })
        await acts().create('r1', 'FDA_PREMARKET', NAMES)
        expect(mutate.mock.calls[1][0].variables.reason).toBe('first filing')
        everyCallIsOneObject()
    })

    it('resubmit requires a reason and sends the version the page shows', async () => {
        preview = { ...PREVIEW, nextVersion: 3, head: HEAD }
        fire.mockResolvedValueOnce({ isConfirmed: true, value: 'updated SBOM' })
        await acts().resubmit(HEAD, NAMES)
        const confirm = fire.mock.calls[0][0]
        expect(confirm.title).toBe('Resubmit?')
        expect(confirm.inputLabel).toBe('Reason (required)')
        expect(confirm.inputValidator('')).toBe('A reason is required')
        expect(confirm.inputValidator('why')).toBeNull()
        expect(query.mock.calls[0][0].variables).toEqual({ release: 'r1', kind: 'FDA_PREMARKET' })
        expect(operation(mutate.mock.calls[0][0])).toBe('ResubmitSubmission')
        expect(mutate.mock.calls[0][0].variables).toEqual({ submission: 's1', reason: 'updated SBOM', expectedLatestVersion: 2 })
        expect(reload).toHaveBeenCalledTimes(1)
        everyCallIsOneObject()
    })

    it('withdraw shows the cancel confirm with a required reason and sends no preview', async () => {
        fire.mockResolvedValueOnce({ isConfirmed: true, value: 'superseded' })
        await acts().withdraw(HEAD, NAMES)
        expect(query).not.toHaveBeenCalled()
        const confirm = fire.mock.calls[0][0]
        expect(confirm.title).toBe('Cancel submission?')
        expect(confirm.confirmButtonText).toBe('Cancel submission')
        expect(fire.mock.calls[1][0].text).toBe('Cancelled.')
        expect(confirm.icon).toBe('warning')
        expect(confirm.inputValidator(' ')).toBe('A reason is required')
        expect(operation(mutate.mock.calls[0][0])).toBe('WithdrawSubmission')
        expect(mutate.mock.calls[0][0].variables).toEqual({ submission: 's1', reason: 'superseded' })
        mutate.mockResolvedValueOnce({ data: { withdrawSubmission: { outcome: 'UNCHANGED', version: null } } })
        fire.mockResolvedValueOnce({ isConfirmed: true, value: 'again' })
        await acts().withdraw(HEAD, NAMES)
        expect(fire.mock.calls.at(-1)![0].text).toBe('Already cancelled.')
        everyCallIsOneObject()
    })

    it('a refused act shows the server text in the object form and reloads; a 503 shows the gateway sentence', async () => {
        const refusal = 'Release <img src=x onerror=alert(1)> 1.2.0: the submission head moved'
        preview = { ...PREVIEW, nextVersion: 3, head: HEAD }
        fire.mockResolvedValueOnce({ isConfirmed: true, value: 'why' })
        mutate.mockRejectedValueOnce(Object.assign(new Error(refusal + '\n' + NULL_TEXT), { errors: [
            { message: refusal, extensions: { classification: 'BAD_REQUEST' } },
            { message: NULL_TEXT, path: ['resubmitSubmission'], extensions: { classification: 'NullValueInNonNullableField' } },
        ] }))
        await acts().resubmit(HEAD, NAMES)
        expect(fire.mock.calls.at(-1)).toEqual([{ title: 'Error!', text: refusal, icon: 'error' }])
        expect(fire.mock.calls.at(-1)![0].text).not.toContain('\n')
        expect(fire.mock.calls.at(-1)![0].text).not.toContain('declared as a non null type')
        expect(reload).toHaveBeenCalledTimes(1)

        fire.mockResolvedValueOnce({ isConfirmed: true, value: 'why' })
        mutate.mockRejectedValueOnce(Object.assign(new Error('Response not successful'), { networkError: { statusCode: 503 } }))
        await acts().withdraw(HEAD, NAMES)
        expect(fire.mock.calls.at(-1)).toEqual([{ title: 'Error!', text: TEA_ACT_GATEWAY_TEXT, icon: 'error' }])
        expect(reload).toHaveBeenCalledTimes(2)
        everyCallIsOneObject()
    })

    it('runs one act at a time: busy during an act, a second call while busy is ignored', async () => {
        const a = acts()
        let answer: (v: any) => void = () => {}
        fire.mockImplementationOnce(() => new Promise(r => { answer = r }))
        const first = a.withdraw(HEAD, NAMES)
        expect(a.busy.value).toBe(true)
        await a.withdraw(HEAD, NAMES)
        await a.create('r1', 'FDA_PREMARKET', NAMES)
        expect(fire).toHaveBeenCalledTimes(1)
        answer({ isConfirmed: false })
        await first
        expect(a.busy.value).toBe(false)
        expect(mutate).not.toHaveBeenCalled()
        everyCallIsOneObject()
    })
})
