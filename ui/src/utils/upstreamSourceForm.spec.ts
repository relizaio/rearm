// T-503, T-504 and T-505 (TPF-185 design 3.6): the form model of an upstream source. The
// validation of a create, the create input and the update input, above all what each sends of
// the credential.
import { describe, expect, it } from 'vitest'
import {
    CredentialChange, buildCreateInput, buildUpdateInput, createModel, createProblems, editModel, intervalWords, rootChangeNeedsCredential, updateProblems,
} from './upstreamSourceForm'
import type { LoadedUpstreamSource, UpstreamSourceFormModel, UpstreamSourceSecrets } from './upstreamSourceForm'

const SECRET = 'S3CR3T-marker-185'
const NO_SECRETS: UpstreamSourceSecrets = { token: '', keySecret: '' }

function complete (over: Partial<UpstreamSourceFormModel> = {}): UpstreamSourceFormModel {
    return { ...createModel(), name: 'Supplier', root: 'https://tea.example.com/tea', ...over }
}

const LOADED: LoadedUpstreamSource = {
    uuid: '3f9a1c2e-5b6d-4e7f-8a9b-0c1d2e3f4a5b',
    name: 'Supplier',
    tea: { root: 'https://tea.example.com/tea' },
    credential: { kind: 'API_KEY', keyId: 'kid-1', setDate: '2026-11-01T10:00:00Z' },
    documentClasses: ['SBOM', 'VEX', 'OTHER'],
    vexImportMode: 'STAGE',
    autoIntegrate: 'DISABLED',
    syncIntervalMinutes: 1440,
    documentReServing: 'WITHHELD',
}

describe('T-503 createProblems', () => {
    it('acceptsACompleteModel', () => {
        expect(createProblems(complete(), NO_SECRETS)).toEqual({})
        expect(createProblems(complete({ credentialKind: 'BEARER_TOKEN' }), { token: SECRET, keySecret: '' })).toEqual({})
        expect(createProblems(complete({ credentialKind: 'API_KEY', keyId: 'kid-1' }), { token: '', keySecret: SECRET })).toEqual({})
    })

    it.each([
        ['an empty name', { name: '' }, NO_SECRETS, 'name'],
        ['a name of spaces', { name: '   ' }, NO_SECRETS, 'name'],
        ['a name of 101 characters', { name: 'n'.repeat(101) }, NO_SECRETS, 'name'],
        ['an empty root', { root: '' }, NO_SECRETS, 'root'],
        ['a root of 1025 characters', { root: 'r'.repeat(1025) }, NO_SECRETS, 'root'],
        ['no document class', { documentClasses: [] }, NO_SECRETS, 'documentClasses'],
        ['an interval of 59', { syncIntervalMinutes: 59 }, NO_SECRETS, 'syncIntervalMinutes'],
        ['an interval of 43201', { syncIntervalMinutes: 43201 }, NO_SECRETS, 'syncIntervalMinutes'],
        ['an interval of 90.5', { syncIntervalMinutes: 90.5 }, NO_SECRETS, 'syncIntervalMinutes'],
        ['no interval', { syncIntervalMinutes: null }, NO_SECRETS, 'syncIntervalMinutes'],
        ['a bearer token kind with an empty token', { credentialKind: 'BEARER_TOKEN' }, NO_SECRETS, 'token'],
        ['an API key with an empty key id', { credentialKind: 'API_KEY', keyId: '' }, { token: '', keySecret: SECRET }, 'keyId'],
        ['an API key with an empty secret', { credentialKind: 'API_KEY', keyId: 'kid-1' }, NO_SECRETS, 'keySecret'],
        ['a token of 8193 characters', { credentialKind: 'BEARER_TOKEN' }, { token: 't'.repeat(8193), keySecret: '' }, 'token'],
    ] as Array<[string, Partial<UpstreamSourceFormModel>, UpstreamSourceSecrets, string]>)('%s is a problem of its field only', (_what, over, secrets, field) => {
        const problems = createProblems(complete(over), secrets)
        expect(Object.keys(problems)).toEqual([field])
        expect(problems[field as keyof typeof problems]).toBeTruthy()
    })

    it('acceptsAndKeepsATokenWithALeadingSpace', () => {
        const secrets = { token: ' ' + SECRET, keySecret: '' }
        const model = complete({ credentialKind: 'BEARER_TOKEN' })
        expect(createProblems(model, secrets)).toEqual({})
        expect(buildCreateInput(model, secrets).credential.token).toBe(' ' + SECRET)
    })
})

describe('T-504 buildCreateInput', () => {
    it('sendsOnlyKindNoneAfterASecretWasTypedForAnotherKind', () => {
        const input = buildCreateInput(complete({ credentialKind: 'NONE', keyId: 'kid-1' }), { token: SECRET, keySecret: SECRET })
        expect(input.credential).toEqual({ kind: 'NONE' })
        expect(JSON.stringify(input)).not.toContain(SECRET)
    })

    it('sendsExactlyTheBearerToken', () => {
        const input = buildCreateInput(complete({ credentialKind: 'BEARER_TOKEN', keyId: 'kid-1' }), { token: SECRET, keySecret: 'other' })
        expect(input.credential).toEqual({ kind: 'BEARER_TOKEN', token: SECRET })
    })

    it('sendsExactlyTheApiKey', () => {
        const input = buildCreateInput(complete({ credentialKind: 'API_KEY', keyId: 'kid-1' }), { token: 'other', keySecret: SECRET })
        expect(input.credential).toEqual({ kind: 'API_KEY', keyId: 'kid-1', keySecret: SECRET })
    })

    it('sendsKindTeaAndTheTrimmedRoot', () => {
        const input = buildCreateInput(complete({ name: '  Supplier  ', root: '  https://tea.example.com/tea  ' }), NO_SECRETS)
        expect(input.kind).toBe('TEA')
        expect(input.tea).toEqual({ root: 'https://tea.example.com/tea' })
        expect(input.name).toBe('Supplier')
        expect(input).toMatchObject({
            documentClasses: ['SBOM', 'VEX', 'OTHER'], vexImportMode: 'STAGE', autoIntegrate: 'DISABLED',
            syncIntervalMinutes: 1440, documentReServing: 'WITHHELD',
        })
    })
})

describe('T-505 buildUpdateInput on a source with a stored API key', () => {
    const start = () => editModel(LOADED)

    it('(a) sendsOnlyTheNameWhenOnlyTheNameChanged', () => {
        const model = { ...start(), name: 'Renamed' }
        expect(buildUpdateInput(LOADED, model, NO_SECRETS)).toEqual({ name: 'Renamed' })
        expect(updateProblems(LOADED, model, NO_SECRETS)).toEqual({})
    })

    it('(b) sendsNothingAndNoCredentialKeyWhenNothingChanged', () => {
        const model = start()
        expect(model.credentialChange).toBe(CredentialChange.KEEP)
        const input = buildUpdateInput(LOADED, model, { token: SECRET, keySecret: SECRET })
        expect(input).toEqual({})
        expect('credential' in input).toBe(false)
        expect(JSON.stringify(input)).not.toContain(SECRET)
    })

    it('(c) sendsTheCompleteBearerCredentialOnReplace', () => {
        const model = { ...start(), credentialChange: CredentialChange.REPLACE, credentialKind: 'BEARER_TOKEN' as const }
        expect(buildUpdateInput(LOADED, model, { token: SECRET, keySecret: '' })).toEqual({ credential: { kind: 'BEARER_TOKEN', token: SECRET } })
    })

    it('(d) sendsKindNoneOnRemove', () => {
        const model = { ...start(), credentialChange: CredentialChange.REMOVE }
        expect(buildUpdateInput(LOADED, model, { token: SECRET, keySecret: SECRET })).toEqual({ credential: { kind: 'NONE' } })
    })

    it('(e) blocksARootChangeThatKeepsTheStoredCredential', () => {
        const root = 'https://other.example.com/tea'
        const keep = { ...start(), root }
        expect(updateProblems(LOADED, keep, NO_SECRETS)).toEqual({ credential: rootChangeNeedsCredential })
        expect(rootChangeNeedsCredential).toBe('The root changes while a credential is stored: enter the credential again or remove it.')

        const replace = { ...start(), root, credentialChange: CredentialChange.REPLACE, credentialKind: 'API_KEY' as const, keyId: 'kid-2' }
        expect(updateProblems(LOADED, replace, { token: '', keySecret: SECRET })).toEqual({})
        expect(buildUpdateInput(LOADED, replace, { token: '', keySecret: SECRET })).toEqual({
            tea: { root }, credential: { kind: 'API_KEY', keyId: 'kid-2', keySecret: SECRET },
        })

        const remove = { ...start(), root, credentialChange: CredentialChange.REMOVE }
        expect(updateProblems(LOADED, remove, NO_SECRETS)).toEqual({})
        expect(buildUpdateInput(LOADED, remove, NO_SECRETS)).toEqual({ tea: { root }, credential: { kind: 'NONE' } })
    })

    it('(f) sendsExactlyTheIntervalAndTheVexMode', () => {
        const model = { ...start(), syncIntervalMinutes: 120, vexImportMode: 'REJECT' as const }
        expect(buildUpdateInput(LOADED, model, NO_SECRETS)).toEqual({ syncIntervalMinutes: 120, vexImportMode: 'REJECT' })
    })

    it('neverPrefillsTheEditModelWithACredential', () => {
        expect(start().keyId).toBe('')
        expect(JSON.stringify(start())).not.toContain('kid-1')
    })

    it('allowsARootChangeWithoutAStoredCredential', () => {
        const none: LoadedUpstreamSource = { ...LOADED, credential: { kind: 'NONE', keyId: null, setDate: null } }
        const model = { ...editModel(none), root: 'https://other.example.com/tea' }
        expect(updateProblems(none, model, NO_SECRETS)).toEqual({})
        expect(buildUpdateInput(none, model, NO_SECRETS)).toEqual({ tea: { root: 'https://other.example.com/tea' } })
    })
})

describe('intervalWords', () => {
    it('saysHoursForMultiplesOf60', () => {
        expect(intervalWords(1440)).toBe('every 24 hours')
        expect(intervalWords(60)).toBe('every hour')
        expect(intervalWords(90)).toBe('every 90 minutes')
        expect(intervalWords(null)).toBe('')
    })
})
