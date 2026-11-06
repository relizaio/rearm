// FDAW-18 (B5), architecture 4 row 17: the Medical device tab's answers, its one input and the lines it
// renders from the server's steps and status.
import { describe, expect, it, vi } from 'vitest'
import {
    EMPTY_DEVICE_PROFILE, MEDICAL_DEVICE_SAVED, MEDICAL_DEVICE_SETUP_LINK_TEXT, QUESTION_FDA_PREMARKET,
    QUESTION_TRACKED_821, SUBMISSIONS_ADMIN_DOES_THIS, SUBMISSIONS_NOT_SET_UP_CELL_LINK, SUBMISSIONS_NOT_SET_UP_TAIL,
    answersFromDeviceClass, completeSetupOffered, deviceClassFromAnswers, deviceProfileDiffers, deviceProfileFormOf,
    deviceStepWritten, fdaCustomizedLine, fdaDefaultsLine, fdaPremarketBaseline, fdaStillOnLine, isCustomized,
    loadMedicalDeviceSetupStatus, medicalDeviceSetupInput, questionIsMedical, setupStatusLines, setupStepLines,
    submissionsNotSetUpCell, submissionsNotSetUpLead, type MedicalDeviceSetupDraft
} from './medicalDeviceSetup'
import { withExpectedRevision } from './componentRevision'

const STORED_PROFILE = { issuingAgency: 'GS1', udiBearing: true, gudidDefaults: { brandName: 'Acme', companyName: null, gmdnCode: null } }

function draft (over: Partial<MedicalDeviceSetupDraft> = {}): MedicalDeviceSetupDraft {
    const profile = deviceProfileFormOf(STORED_PROFILE)
    return {
        deviceClass: 'MEDICAL_UNTRACKED', deviceClassBaseline: 'MEDICAL_UNTRACKED',
        window: { eos: '2031-01-31', eol: null }, windowBaseline: { eos: '2031-01-31', eol: null },
        profile: { ...profile }, profileBaseline: { ...profile },
        fdaShown: true, fdaPremarket: 'ENABLED', productPackage: null, orgPackageProfile: 'default',
        ...over
    }
}

describe('question 1 and the device class', () => {
    it('maps the two answers to the class and back', () => {
        expect(deviceClassFromAnswers(false, false)).toBe('NONE')
        expect(deviceClassFromAnswers(false, true)).toBe('NONE')
        expect(deviceClassFromAnswers(true, false)).toBe('MEDICAL_UNTRACKED')
        expect(deviceClassFromAnswers(true, true)).toBe('MEDICAL_TRACKED')
        for (const c of ['NONE', 'MEDICAL_UNTRACKED', 'MEDICAL_TRACKED']) {
            const a = answersFromDeviceClass(c)
            expect(deviceClassFromAnswers(a.isDevice, a.tracked), c).toBe(c)
        }
        expect(answersFromDeviceClass(null)).toEqual({ isDevice: false, tracked: false })
    })
})

describe('medicalDeviceSetupInput', () => {
    it('sends the class, no profile when no UDI field changed, and no window part when the dates did not change', () => {
        expect(medicalDeviceSetupInput(draft())).toEqual({ deviceClass: 'MEDICAL_UNTRACKED', medicalProfile: null,
            fdaPremarket: 'ENABLED', fdaSbomProfile: null })
    })

    it('sends the window part by deviceSaveInput rules: new dates, or the clear flag on blanked dates', () => {
        expect(medicalDeviceSetupInput(draft({ window: { eos: '2032-01-31', eol: '2030-01-01' } })).deviceSupportWindow)
            .toEqual({ eos: '2032-01-31', eol: '2030-01-01' })
        const cleared = medicalDeviceSetupInput(draft({ window: { eos: null, eol: null } }))
        expect(cleared.clearDeviceSupportWindow).toBe(true)
        expect(cleared).not.toHaveProperty('deviceSupportWindow')
    })

    it('under NONE: the class alone, no window part, no profile, no FDA answer (F-8)', () => {
        const none = medicalDeviceSetupInput(draft({ deviceClass: 'NONE', window: { eos: '2040-01-01', eol: null },
            profile: { ...EMPTY_DEVICE_PROFILE, brandName: 'x' } }))
        expect(none).toEqual({ deviceClass: 'NONE', medicalProfile: null, fdaPremarket: null, fdaSbomProfile: null })
    })

    it('sends the whole profile when one UDI field changed (F-6)', () => {
        const d = draft()
        d.profile.issuingAgency = 'HIBCC'
        expect(medicalDeviceSetupInput(d).medicalProfile).toEqual({ issuingAgency: 'HIBCC', udiBearing: true,
            gudidDefaults: { brandName: 'Acme', companyName: null, gmdnCode: null } })
        const g = draft()
        g.profile.gmdnCode = ' 12345 '
        expect(medicalDeviceSetupInput(g).medicalProfile).toEqual({ issuingAgency: 'GS1', udiBearing: true,
            gudidDefaults: { brandName: 'Acme', companyName: null, gmdnCode: '12345' } })
        const blank = draft({ profile: { ...EMPTY_DEVICE_PROFILE }, profileBaseline: { ...EMPTY_DEVICE_PROFILE, udiBearing: true } })
        expect(medicalDeviceSetupInput(blank).medicalProfile).toEqual({ issuingAgency: null, udiBearing: false, gudidDefaults: null })
    })

    it('sends no FDA answer when the FDA half is hidden, and the answer when shown', () => {
        expect(medicalDeviceSetupInput(draft({ fdaShown: false })).fdaPremarket).toBeNull()
        expect(medicalDeviceSetupInput(draft({ fdaPremarket: 'DISABLED' })).fdaPremarket).toBe('DISABLED')
    })

    it('names the profile only for a Customized row', () => {
        const custom = { mode: 'OVERRIDE', state: 'ENABLED', sbomProfile: 'mine', parts: [] }
        expect(medicalDeviceSetupInput(draft({ productPackage: custom })).fdaSbomProfile).toBe('mine')
        const defaults = { mode: 'OVERRIDE', state: 'ENABLED', sbomProfile: 'default', parts: [] }
        expect(medicalDeviceSetupInput(draft({ productPackage: defaults })).fdaSbomProfile).toBeNull()
        expect(medicalDeviceSetupInput(draft({ productPackage: custom, fdaShown: false })).fdaSbomProfile).toBeNull()
    })

    it('carries the expected revision through withExpectedRevision', () => {
        expect(withExpectedRevision(medicalDeviceSetupInput(draft()), 7)).toEqual({ deviceClass: 'MEDICAL_UNTRACKED',
            medicalProfile: null, fdaPremarket: 'ENABLED', fdaSbomProfile: null, expectedRevision: 7 })
    })
})

describe('the UDI form', () => {
    it('reads a stored profile, a null one as empty, and tells a change from none', () => {
        expect(deviceProfileFormOf(STORED_PROFILE)).toEqual({ issuingAgency: 'GS1', udiBearing: true, brandName: 'Acme',
            companyName: '', gmdnCode: '' })
        expect(deviceProfileFormOf(null)).toEqual(EMPTY_DEVICE_PROFILE)
        const a = deviceProfileFormOf(STORED_PROFILE)
        expect(deviceProfileDiffers(a, { ...a })).toBe(false)
        for (const k of Object.keys(a) as (keyof typeof a)[]) {
            const b: any = { ...a }
            b[k] = typeof b[k] === 'boolean' ? !b[k] : 'other'
            expect(deviceProfileDiffers(b, a), k).toBe(true)
        }
    })
})

describe('question 2, the defaults line and Complete setup', () => {
    it('isCustomized (F-2): another name or parts, never default or the organization name with no parts', () => {
        expect(isCustomized(null, 'fda')).toBe(false)
        expect(isCustomized({ sbomProfile: 'default', parts: [] }, 'fda')).toBe(false)
        expect(isCustomized({ sbomProfile: 'fda', parts: null }, 'fda')).toBe(false)
        expect(isCustomized({ sbomProfile: 'custom', parts: [] }, 'fda')).toBe(true)
        expect(isCustomized({ sbomProfile: 'fda', parts: [{ part: 'VEX' }] }, 'fda')).toBe(true)
        expect(isCustomized({ mode: 'FOLLOW_PERSPECTIVE', sbomProfile: null }, 'fda')).toBe(false)
    })

    it('fdaPremarketBaseline (F-1): the row state, Yes when there is no row', () => {
        expect(fdaPremarketBaseline({ productPackage: null })).toBe('ENABLED')
        expect(fdaPremarketBaseline(null)).toBe('ENABLED')
        expect(fdaPremarketBaseline({ productPackage: { mode: 'OVERRIDE', state: 'DISABLED' } })).toBe('DISABLED')
        expect(fdaPremarketBaseline({ productPackage: { mode: 'OVERRIDE', state: 'ENABLED' } })).toBe('ENABLED')
        expect(fdaPremarketBaseline({ productPackage: { mode: 'FOLLOW_PERSPECTIVE' }, productState: 'ENABLED' })).toBe('ENABLED')
    })

    it('completeSetupOffered: only a non-empty pendingForCaller with both questions yes', () => {
        const pending = { pending: ['ORG_SUPPORT_EXPORT'], pendingForCaller: ['PRODUCT_PACKAGE_SETTINGS'] }
        expect(completeSetupOffered(pending, { isDevice: true, fdaPremarket: 'ENABLED' })).toBe(true)
        expect(completeSetupOffered(pending, { isDevice: false, fdaPremarket: 'ENABLED' })).toBe(false)
        expect(completeSetupOffered(pending, { isDevice: true, fdaPremarket: 'DISABLED' })).toBe(false)
        // pending alone (another caller's steps) offers nothing (F-10)
        expect(completeSetupOffered({ pending: ['ORG_SUPPORT_EXPORT'], pendingForCaller: [] },
            { isDevice: true, fdaPremarket: 'ENABLED' })).toBe(false)
        expect(completeSetupOffered(null, { isDevice: true, fdaPremarket: 'ENABLED' })).toBe(false)
    })
})

describe('the save result', () => {
    const result = (device: string) => ({ steps: [
        { step: 'ORG_SUPPORT_EXPORT', outcome: 'NOT_AUTHORIZED', text: 'org sentence' },
        { step: 'ORG_FDA_PROFILE', outcome: 'NOT_AUTHORIZED', text: 'org sentence' },
        { step: 'ORG_PACKAGE_SETTINGS', outcome: 'UNCHANGED', text: null },
        { step: 'PRODUCT_PACKAGE_SETTINGS', outcome: 'WRITTEN', text: 'written' },
        { step: 'DEVICE_ANSWERS', outcome: device, text: null }] })

    it('deviceStepWritten (F-4): true for WRITTEN, false for UNCHANGED or no steps', () => {
        expect(deviceStepWritten(result('WRITTEN'))).toBe(true)
        expect(deviceStepWritten(result('UNCHANGED'))).toBe(false)
        expect(deviceStepWritten(null)).toBe(false)
    })

    it('setupStepLines: one line per NOT_AUTHORIZED step with its text, none for the others', () => {
        expect(setupStepLines(result('WRITTEN'))).toEqual([
            { step: 'ORG_SUPPORT_EXPORT', text: 'org sentence' }, { step: 'ORG_FDA_PROFILE', text: 'org sentence' }])
        expect(setupStepLines({ steps: [{ step: 'X', outcome: 'NOT_REQUESTED', text: null }] })).toEqual([])
        expect(setupStepLines(undefined)).toEqual([])
    })

    it('setupStatusLines: the three organization steps, done or not', () => {
        expect(setupStatusLines({ supportExport: 'DISABLED', fdaProfile: 'ABSENT', orgPackageProfile: 'default' })).toEqual([
            { step: 'ORG_SUPPORT_EXPORT', done: false, text: 'Support metadata export: off' },
            { step: 'ORG_FDA_PROFILE', done: false, text: 'SBOM profile fda: absent' },
            { step: 'ORG_PACKAGE_SETTINGS', done: false, text: 'Organization FDA package settings: SBOM profile default' }])
        expect(setupStatusLines({ supportExport: 'ENABLED', fdaProfile: 'INCLUDES_SUPPORT', orgPackageProfile: 'fda' })
            .map(l => l.done)).toEqual([true, true, true])
        expect(setupStatusLines({ fdaProfile: 'EXCLUDES_SUPPORT' })[1].text).toBe('SBOM profile fda: excludes support metadata')
        expect(setupStatusLines(null)).toEqual([])
    })
})

describe('the texts of 3.5 and 3.6', () => {
    it('reads byte for byte', () => {
        expect(questionIsMedical('Pump')).toBe('Is Pump a medical device?')
        expect(QUESTION_TRACKED_821).toBe('Subject to an FDA tracking order (21 CFR 821)')
        expect(QUESTION_FDA_PREMARKET).toBe('Prepare FDA premarket submissions (US)?')
        expect(fdaDefaultsLine('default')).toBe('Using organization defaults: SBOM profile default')
        expect(fdaCustomizedLine('mine')).toBe('Customized: SBOM profile mine')
        expect(MEDICAL_DEVICE_SAVED).toBe('Medical device settings saved')
        expect(fdaStillOnLine('Pump')).toBe('FDA premarket submissions stay switched on for Pump: switch them off under Settings > Regulatory')
        expect(submissionsNotSetUpLead('Pump') + MEDICAL_DEVICE_SETUP_LINK_TEXT + SUBMISSIONS_NOT_SET_UP_TAIL)
            .toBe('No submission yet. Pump is not set up for FDA premarket submissions: set it up under Settings > Medical device (one save).')
        expect(submissionsNotSetUpCell('FDA premarket')).toBe('Not set up: FDA premarket is switched off for this product.')
        expect(SUBMISSIONS_NOT_SET_UP_CELL_LINK).toBe('Set it up under Settings > Medical device')
        expect(SUBMISSIONS_ADMIN_DOES_THIS).toBe('(the organization steps need an organization administrator)')
    })
})

describe('loadMedicalDeviceSetupStatus', () => {
    const drift = { graphQLErrors: [{ message: "Validation error (FieldUndefined@[medicalDeviceSetupStatus]) : Field 'medicalDeviceSetupStatus' in type 'Query' is undefined", extensions: { classification: 'ValidationError' } }] }

    it('answers the status from the full document', async () => {
        const status = { supportExport: 'ENABLED', pendingForCaller: [] }
        const client = { query: vi.fn(async () => ({ data: { component: { uuid: 'c1' }, medicalDeviceSetupStatus: status } })) }
        expect(await loadMedicalDeviceSetupStatus(client as any, 'c1')).toEqual({ supported: true, status })
        expect(client.query).toHaveBeenCalledTimes(1)
        expect((client.query.mock.calls[0] as any)[0].variables).toEqual({ componentUuid: 'c1' })
        expect((client.query.mock.calls[0] as any)[0].fetchPolicy).toBe('no-cache')
    })

    it('hides the FDA half on a backend without the field: the core document answers', async () => {
        const client = { query: vi.fn(async (o: any) => {
            if (o.query.loc.source.body.includes('medicalDeviceSetupStatus(component')) throw drift
            return { data: { component: { uuid: 'c1' } } }
        }) }
        expect(await loadMedicalDeviceSetupStatus(client as any, 'c1')).toEqual({ supported: false, status: null })
        expect(client.query).toHaveBeenCalledTimes(2)
    })

    it('throws a real failure', async () => {
        const boom = new Error('network down')
        const client = { query: vi.fn(async () => { throw boom }) }
        await expect(loadMedicalDeviceSetupStatus(client as any, 'c1')).rejects.toBe(boom)
    })
})
