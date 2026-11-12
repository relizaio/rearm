// FDAW-81, architecture round 3 section 4 row 7 (REQ-76, REQ-77): question 2 = Yes on a product includes the
// device support statement. The tab reads the stored selection (statementSetupState mirrors the server's
// storedStatementSelection), a row holding only the statement INCLUDE is not "Customized", and the status
// documents read the selection.
import { describe, expect, it } from 'vitest'
import { print } from 'graphql'
import {
    MEDICAL_DEVICE_SETUP_STATUS_QUERY, SET_UP_MEDICAL_DEVICE, isCustomized, medicalDeviceSetupInput,
    deviceProfileFormOf, statementSetupLine, statementSetupState, type MedicalDeviceSetupDraft
} from './medicalDeviceSetup'

const S = (sel: any) => ({ part: 'DEVICE_SUPPORT_STATEMENT', options: { selection: sel } })
const VEX = (sel: string) => ({ part: 'VEX', options: { selection: sel } })
const own = (parts: any, over: Record<string, unknown> = {}) => ({ productPackage: { mode: 'OVERRIDE', state: 'ENABLED',
    sbomProfile: 'fda', parts, ...over } })

function draft (productPackage: any): MedicalDeviceSetupDraft {
    const profile = deviceProfileFormOf(null)
    return {
        deviceClass: 'MEDICAL_UNTRACKED', deviceClassBaseline: 'MEDICAL_UNTRACKED',
        window: { eos: null, eol: null }, windowBaseline: { eos: null, eol: null },
        profile: { ...profile }, profileBaseline: { ...profile },
        fdaShown: true, fdaPremarket: 'ENABLED', productPackage, orgPackageProfile: 'fda'
    }
}

describe('isCustomized with the statement the setup writes (FDAW-81)', () => {
    it('a row holding only the statement INCLUDE is not customized; anything else stored is', () => {
        expect(isCustomized({ sbomProfile: 'fda', parts: [S('INCLUDE')] }, 'fda')).toBe(false)
        expect(isCustomized({ sbomProfile: 'default', parts: [S('INCLUDE')] }, 'fda')).toBe(false)
        expect(isCustomized({ sbomProfile: 'fda', parts: [S('EXCLUDE')] }, 'fda')).toBe(true)
        expect(isCustomized({ sbomProfile: 'fda', parts: [S(null)] }, 'fda')).toBe(true)
        expect(isCustomized({ sbomProfile: 'fda', parts: [{ part: 'DEVICE_SUPPORT_STATEMENT' }] }, 'fda')).toBe(true)
        expect(isCustomized({ sbomProfile: 'fda', parts: [S('INCLUDE'), VEX('INCLUDE')] }, 'fda')).toBe(true)
        expect(isCustomized({ sbomProfile: 'fda', parts: [VEX('INCLUDE')] }, 'fda')).toBe(true)
        expect(isCustomized({ sbomProfile: 'custom', parts: [S('INCLUDE')] }, 'fda')).toBe(true)
        expect(isCustomized({ mode: 'FOLLOW_PERSPECTIVE', sbomProfile: null, parts: [S('EXCLUDE')] }, 'fda')).toBe(false)
    })

    it('medicalDeviceSetupInput: a default-named row the setup wrote keeps following the organization name', () => {
        const written = { mode: 'OVERRIDE', state: 'ENABLED', sbomProfile: 'default', parts: [S('INCLUDE')] }
        expect(medicalDeviceSetupInput(draft(written)).fdaSbomProfile).toBeNull()
        expect(medicalDeviceSetupInput(draft({ ...written, parts: [S('INCLUDE'), VEX('EXCLUDE')] })).fdaSbomProfile)
            .toBe('default')
    })
})

describe('statementSetupState (FDAW-81)', () => {
    it('no status is null', () => {
        expect(statementSetupState(null)).toBeNull()
        expect(statementSetupState(undefined)).toBeNull()
    })

    it('no own row, a FOLLOW_PERSPECTIVE row or no stored selection is NOT_YET', () => {
        expect(statementSetupState({ productPackage: null })).toBe('NOT_YET')
        expect(statementSetupState({ productPackage: { mode: 'FOLLOW_PERSPECTIVE', parts: [S('INCLUDE')] } })).toBe('NOT_YET')
        for (const parts of [null, [], [VEX('EXCLUDE')], [S(null)], [{ part: 'DEVICE_SUPPORT_STATEMENT' }]]) {
            expect(statementSetupState(own(parts)), JSON.stringify(parts)).toBe('NOT_YET')
        }
    })

    it('a stored selection is read whatever the row state and the entry position', () => {
        expect(statementSetupState(own([S('INCLUDE')]))).toBe('INCLUDED')
        expect(statementSetupState(own([S('EXCLUDE')]))).toBe('EXCLUDED')
        expect(statementSetupState(own([S('INCLUDE')], { state: 'DISABLED' }))).toBe('INCLUDED')
        expect(statementSetupState(own([VEX('EXCLUDE'), S('EXCLUDE')]))).toBe('EXCLUDED')
    })
})

describe('statementSetupLine (FDAW-81 3.4)', () => {
    it('the three texts as written', () => {
        expect(statementSetupLine('INCLUDED')).toBe('Device support statement: included in this product\'s FDA premarket'
            + ' packages. Its text comes from the organization\'s three labeling statements; until they are written, a'
            + ' package that includes it is refused.')
        expect(statementSetupLine('NOT_YET')).toBe('Device support statement: not set for this product yet. Save with Yes'
            + ' includes it in this product\'s FDA premarket packages; that step needs a product administrator.')
        expect(statementSetupLine('EXCLUDED')).toBe('Device support statement: excluded for this product under Settings >'
            + ' Regulatory. Save does not change that choice.')
    })
})

describe('the setup documents read the stored selection (FDAW-81)', () => {
    it('the status query and the save mutation select parts { part options { selection } }', () => {
        for (const doc of [MEDICAL_DEVICE_SETUP_STATUS_QUERY, SET_UP_MEDICAL_DEVICE]) {
            expect(print(doc).replace(/\s+/g, ' ')).toContain('parts { part options { selection } }')
        }
    })
})
