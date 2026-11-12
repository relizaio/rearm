import { describe, it, expect, vi } from 'vitest'
import { print } from 'graphql'
import {
    DISPLAYED_VERSION_CLEAR, DISPLAYED_VERSION_CLEARED, DISPLAYED_VERSION_DOCUMENT_LABEL, DISPLAYED_VERSION_HEADING,
    DISPLAYED_VERSION_HINT, DISPLAYED_VERSION_INTRO, DISPLAYED_VERSION_MAX_LENGTH, DISPLAYED_VERSION_PLACEHOLDER,
    DISPLAYED_VERSION_SAVE, DISPLAYED_VERSION_SAVED, DISPLAYED_VERSION_SHIPPED_NOTE, DISPLAYED_VERSION_STATEMENT_PREFIX,
    RELEASE_DISPLAYED_VERSION_QUERY, UPDATE_RELEASE_DISPLAYED_VERSION, displayedVersionHistoryText,
    displayedVersionVariables, isShippedOrLater, loadReleaseDisplayedVersion,
} from './displayedVersion'

// FDAW-74 row 10 (3.7): the pure parts of the release page's displayed version.

function driftError (): any {
    const err: any = new Error('Validation error (FieldUndefined@[release/displayedVersion]) : Field \'displayedVersion\' in type \'Release\' is undefined')
    err.errors = [{ message: err.message, extensions: { classification: 'ValidationError' } }]
    return err
}

describe('displayedVersionVariables', () => {
    it('sends the trimmed change, the empty string as the clear, nothing when equal', () => {
        expect(displayedVersionVariables('r1', 'o1', 'GV-2.4.2', ''))
            .toEqual({ release: { uuid: 'r1', org: 'o1', displayedVersion: 'GV-2.4.2' } })
        expect(displayedVersionVariables('r1', 'o1', '  GV-2.4.2 ', 'GV-2.4.2')).toBeNull()
        expect(displayedVersionVariables('r1', 'o1', '', 'GV-2.4.2'))
            .toEqual({ release: { uuid: 'r1', org: 'o1', displayedVersion: '' } })
        expect(displayedVersionVariables('r1', 'o1', '   ', '')).toBeNull()
        expect(displayedVersionVariables('r1', 'o1', 'GV-2.4.2', 'GV-2.4.1 (Build 118)'))
            .toEqual({ release: { uuid: 'r1', org: 'o1', displayedVersion: 'GV-2.4.2' } })
    })
})

describe('displayedVersionHistoryText', () => {
    it('reads each action in words, and a value as text', () => {
        expect(displayedVersionHistoryText({ rua: 'ADDED', newValue: 'GV-2.4.2' })).toBe('Displayed version set to "GV-2.4.2"')
        expect(displayedVersionHistoryText({ rua: 'CHANGED', oldValue: 'GV-2.4.1 (Build 118)', newValue: 'GV-2.4.2' }))
            .toBe('Displayed version changed from "GV-2.4.1 (Build 118)" to "GV-2.4.2"')
        expect(displayedVersionHistoryText({ rua: 'REMOVED', oldValue: 'GV-2.4.2' })).toBe('Displayed version cleared (was "GV-2.4.2")')
        expect(displayedVersionHistoryText({ rua: 'ADDED' })).toBe('Displayed version updated')
        expect(displayedVersionHistoryText({ rua: 'CHANGED', newValue: 'x' })).toBe('Displayed version updated')
        expect(displayedVersionHistoryText({ rua: 'UPDATED', oldValue: 'a', newValue: 'b' })).toBe('Displayed version updated')
        expect(displayedVersionHistoryText({ rua: 'ADDED', newValue: '<b>x</b>' })).toBe('Displayed version set to "<b>x</b>"')
    })
})

describe('isShippedOrLater', () => {
    it('is true from GENERAL_AVAILABILITY on, false before and for nothing', () => {
        for (const l of ['GENERAL_AVAILABILITY', 'END_OF_MARKETING', 'END_OF_DISTRIBUTION', 'END_OF_SUPPORT', 'END_OF_LIFE']) {
            expect(isShippedOrLater(l)).toBe(true)
        }
        for (const l of ['READY_TO_SHIP', 'DRAFT', '', undefined]) expect(isShippedOrLater(l)).toBe(false)
    })
})

describe('loadReleaseDisplayedVersion', () => {
    const client = (answer: () => Promise<any>) => ({ query: vi.fn(answer) })
    it('reads the value, null for a missing node or field, unsupported on drift, rethrows anything else', async () => {
        expect(await loadReleaseDisplayedVersion(client(async () => ({ data: { release: { uuid: 'r1', displayedVersion: 'GV-2.4.2' } } })), 'r1', 'o1'))
            .toEqual({ supported: true, value: 'GV-2.4.2' })
        expect(await loadReleaseDisplayedVersion(client(async () => ({ data: {} })), 'r1', 'o1'))
            .toEqual({ supported: true, value: null })
        expect(await loadReleaseDisplayedVersion(client(async () => ({ data: { release: { uuid: 'r1', displayedVersion: null } } })), 'r1', 'o1'))
            .toEqual({ supported: true, value: null })
        expect(await loadReleaseDisplayedVersion(client(async () => { throw driftError() }), 'r1', 'o1'))
            .toEqual({ supported: false, value: null })
        await expect(loadReleaseDisplayedVersion(client(async () => { throw new Error('boom') }), 'r1', 'o1'))
            .rejects.toThrow('boom')
    })

    it('asks with the release and organization, uncached', async () => {
        const c = client(async () => ({ data: {} }))
        await loadReleaseDisplayedVersion(c, 'r1', 'o1')
        expect(c.query).toHaveBeenCalledWith({ query: RELEASE_DISPLAYED_VERSION_QUERY, variables: { releaseID: 'r1', orgID: 'o1' }, fetchPolicy: 'no-cache' })
    })
})

describe('the documents and the copy', () => {
    const collapsed = (doc: any) => print(doc).replace(/\s+/g, ' ')
    it('both documents select uuid and displayedVersion', () => {
        expect(collapsed(RELEASE_DISPLAYED_VERSION_QUERY)).toContain('uuid displayedVersion')
        expect(collapsed(UPDATE_RELEASE_DISPLAYED_VERSION)).toContain('uuid displayedVersion')
    })

    it('the bound and every text of the block, word for word', () => {
        expect(DISPLAYED_VERSION_MAX_LENGTH).toBe(128)
        expect(DISPLAYED_VERSION_DOCUMENT_LABEL).toBe('Displayed version')
        expect(DISPLAYED_VERSION_STATEMENT_PREFIX).toBe('Shown on the device as ')
        expect(DISPLAYED_VERSION_HEADING).toBe('Displayed version')
        expect(DISPLAYED_VERSION_INTRO).toBe('The version the device shows its user, for example on its About screen. It is '
            + 'written into the release SBOM, the support addendum and the device support statement, so that a customer can '
            + 'match this release to the device in front of them. Optional.')
        expect(DISPLAYED_VERSION_SHIPPED_NOTE).toBe('This release is shipped. If its TEA profile publishes at shipped, saving '
            + 'a change publishes this release to TEA again at once, with a new SBOM revision.')
        expect(DISPLAYED_VERSION_PLACEHOLDER).toBe('Not set')
        expect(DISPLAYED_VERSION_SAVE).toBe('Save displayed version')
        expect(DISPLAYED_VERSION_CLEAR).toBe('Clear')
        expect(DISPLAYED_VERSION_HINT).toBe('At most 128 characters, on one line. A change applies to documents built after '
            + 'it is saved: rebuild a draft package to carry it. On TEA it appears with the next publication of this release. '
            + 'Package versions that are already final and TEA revisions that are already published are not rewritten.')
        expect(DISPLAYED_VERSION_SAVED).toBe('Displayed version saved.')
        expect(DISPLAYED_VERSION_CLEARED).toBe('Displayed version cleared.')
    })
})
