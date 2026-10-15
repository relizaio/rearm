import { describe, expect, it } from 'vitest'
import { canDeclareKey, canReleaseKeyName, DECLARABLE_KEY_TYPES, declaredNameProblem, declaredSourceDetail, declaredSourceLabel,
    declarePayload, INVISIBLE_NAME, MAX_DECLARED_NAME, NOT_NFC_NAME, releasePayload, secretExpiresLabel } from './apiKeyDeclaration'
import { tsFull } from './agentTaskFormat'

const key = (type: string, status: string = 'ACTIVE', declaredName: string | null = null) => ({ uuid: 'k1', type, status, declaredName })

describe('apiKeyDeclaration', () => {
    it('offers "Declare as…" to an org admin on a live key of a declarable type only', () => {
        expect(DECLARABLE_KEY_TYPES).toEqual(['FREEFORM'])
        expect(canDeclareKey(key('FREEFORM', 'ACTIVE'), true)).toBe(true)
        expect(canDeclareKey(key('FREEFORM', 'INACTIVE'), true)).toBe(true)
        expect(canDeclareKey(key('FREEFORM', 'REQUESTED'), true)).toBe(false)
        expect(canDeclareKey(key('FREEFORM', 'DENIED'), true)).toBe(false)
        // ORGANIZATION and ORGANIZATION_RW keys are deprecated; component keys stay with their components.
        for (const t of ['ORGANIZATION', 'ORGANIZATION_RW', 'COMPONENT', 'USER', 'FEDERATED', 'INSTANCE', 'CLUSTER', 'REGISTRY_USER', 'APPROVAL']) {
            expect(canDeclareKey(key(t), true)).toBe(false)
        }
    })

    it('offers nothing to a viewer who is not an org admin', () => {
        expect(canDeclareKey(key('FREEFORM'), false)).toBe(false)
        expect(canDeclareKey(key('FREEFORM', 'ACTIVE', 'ci'), false)).toBe(false)
        expect(canReleaseKeyName(key('FREEFORM', 'ACTIVE', 'ci'), false)).toBe(false)
        expect(canDeclareKey(null, true)).toBe(false)
    })

    it('offers "Release name" on a key that carries one', () => {
        expect(canReleaseKeyName(key('FREEFORM', 'ACTIVE', 'ci'), true)).toBe(true)
        expect(canReleaseKeyName(key('FREEFORM', 'INACTIVE', 'ci'), true)).toBe(true)
        expect(canReleaseKeyName(key('FREEFORM'), true)).toBe(false)
        expect(canReleaseKeyName(key('FREEFORM', 'ACTIVE', ''), true)).toBe(false)
    })

    it('takes a name the server would take', () => {
        for (const ok of ['ci', 'release pipeline', 'a'.repeat(MAX_DECLARED_NAME), 'repo/x:y']) expect(declaredNameProblem(ok)).toBe('')
        expect(MAX_DECLARED_NAME).toBe(128)
    })

    it('refuses a blank, padded, over-long or control-character name', () => {
        for (const blank of [null, undefined, '', '   ']) expect(declaredNameProblem(blank)).toBe('A key needs a name')
        for (const padded of [' ci', 'ci ', '\tci', 'ci\n']) expect(declaredNameProblem(padded)).toBe('A key\'s name has no leading or trailing spaces')
        expect(declaredNameProblem('a'.repeat(MAX_DECLARED_NAME + 1))).toBe('A key\'s name is at most 128 characters')
        expect(declaredNameProblem('c\u0007i')).toBe('A key\'s name has no control characters')
        expect(declaredNameProblem('c\u0085i')).toBe('A key\'s name has no control characters')
    })

    // The server's cases for a name being assigned, written as escapes so the file stays ASCII.
    const refused = (name: string) => expect(declaredNameProblem(name).startsWith(INVISIBLE_NAME), name).toBe(true)
    const kept = (name: string) => expect(declaredNameProblem(name), name).toBe('')

    it('refuses control, format, bidirectional, separator and private-use characters', () => {
        for (const name of ['a\u202Eb', 'ci\u200Brelease', 'soft\u00ADhyphen', 'ci\uFEFFrelease', 'a\u2028b', 'pua\uE000x', 'a\uD800b']) refused(name)
    })

    it('refuses stray whitespace inside a name', () => {
        for (const name of ['two  spaces', 'no\u00A0break', 'ideographic\u3000space']) refused(name)
    })

    it('keeps a joiner only where it shapes: an emoji sequence, or one joining script on both sides', () => {
        for (const name of ['ci\u200Drelease', '\u0645\u200Dx', 'x\u200D\u0645', '\uD55C\u200D\uAE00 key']) refused(name)
        for (const name of ['\uD83D\uDC69\u200D\uD83D\uDCBB ops', '\u0645\u200C\u0646', '\u0915\u094D\u200D\u0937',
            '\uD83D\uDC69\uD83C\uDFFD\u200D\uD83D\uDCBB ops', '\uD83C\uDFF3\uFE0F\u200D\uD83C\uDF08 ops', '\uD83D\uDC69\u200D\u2695\uFE0F ops',
            '\u0645\u064E\u200C\u0646', '\u1004\u103A\u1039\u200D\u1000']) kept(name)
    })

    it('keeps a variation selector only where it changes how an emoji or a keycap shows', () => {
        for (const name of ['ci-deploy\uFE0F', 'caf\u00E9\uFE0F key', 'd\u00E9\uDB40\uDD00ploy', '\uD83D\uDE80\uFE0F ops']) refused(name)
        for (const name of ['\u2764\uFE0F ops', 'deploy 1\uFE0F\u20E3', 'ops #\u20E3']) kept(name)
    })

    it('keeps tags only in a complete flag sequence', () => {
        kept('\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC65\uDB40\uDC6E\uDB40\uDC67\uDB40\uDC7F ops')
        for (const name of ['ci\uDB40\uDC67deploy', 'ci\uDB40\uDC7Fdeploy', '\uD83C\uDFF4\uDB40\uDC67 ops']) refused(name)
    })

    it('refuses blank letters, and combining marks without a visible base', () => {
        for (const name of ['ci\u3164deploy', 'ci\uFFA0deploy', 'ci\u2800deploy', 'ci\uD834\uDD59release', 'caf\u00E9\u034F key',
            '\u0301a', 'ab \u0301c', '\u0645\u200D\u0301']) refused(name)
        for (const name of ['g\u0303 ops', 'n\u0304 key', '\u00E9t\u00E9 key', 'ci release', '\uD55C\uAE00 key']) kept(name)
    })

    it('refuses a name not in composed form', () => {
        expect(declaredNameProblem('e\u0301te key')).toBe(NOT_NFC_NAME)
        expect(declaredNameProblem('\u212Aey')).toBe(NOT_NFC_NAME)
    })

    it('names the refused character and its place, counted in code points', () => {
        expect(declaredNameProblem('ci\u200Brelease')).toBe(INVISIBLE_NAME + ' (U+200B at code point 3)')
        expect(declaredNameProblem('x\uD83D\uDE80ci\uD834\uDD59')).toBe(INVISIBLE_NAME + ' (U+1D159 at code point 5)')
    })

    it('lets a key keep a name it already carries, as the server does, but still checks the rest', () => {
        expect(declaredNameProblem('ci\u200Brelease', 'ci\u200Brelease')).toBe('')
        refused('ci\u200Brelease')
        expect(declaredNameProblem('ci\u200Brelease', 'other').startsWith(INVISIBLE_NAME)).toBe(true)
        expect(declaredNameProblem(' ci', ' ci')).toBe('A key\'s name has no leading or trailing spaces')
    })

    it('declares with the name as typed and releases with a null name', () => {
        expect(declarePayload('k1', 'ci')).toEqual({ apiKeyUuid: 'k1', name: 'ci' })
        // never tidied: the name check refuses padding, so what is sent is what was checked
        expect(declarePayload('k1', ' ci ')).toEqual({ apiKeyUuid: 'k1', name: ' ci ' })
        const released = releasePayload('k1')
        expect(released).toEqual({ apiKeyUuid: 'k1', name: null })
        expect(released.name).toBeNull()
    })

    it('says where a declared key came from', () => {
        expect(declaredSourceLabel(null)).toBe('')
        expect(declaredSourceLabel({ source: { repo: 'github.com/acme/infra', path: 'rearm/keys.yaml', commit: 'abc' } }))
            .toBe('declared in github.com/acme/infra/rearm/keys.yaml')
        expect(declaredSourceLabel({ source: { repo: 'github.com/acme/infra/', path: '/keys.yaml' } })).toBe('declared in github.com/acme/infra/keys.yaml')
        expect(declaredSourceLabel({ source: { repo: 'github.com/acme/infra' } })).toBe('declared in github.com/acme/infra')
        expect(declaredSourceLabel({ source: { path: 'keys.yaml' } })).toBe('declared in keys.yaml')
        expect(declaredSourceLabel({ appliedAt: '2026-09-28T10:00:00Z', source: null })).toBe('declared in a file')
    })

    it('puts the commit short sha and the applied time on hover', () => {
        const at = '2026-09-28T10:00:00Z'
        expect(declaredSourceDetail({ appliedAt: at, source: { repo: 'r', path: 'p', commit: '0123456789abcdef' } }))
            .toBe(`commit 01234567 · applied ${tsFull(at)}`)
        expect(declaredSourceDetail({ appliedAt: at, source: { repo: 'r' } })).toBe(`applied ${tsFull(at)}`)
        expect(declaredSourceDetail({ source: { commit: 'feedbeef00' } })).toBe('commit feedbeef')
        expect(declaredSourceDetail(null)).toBe('')
    })

    it('names the life of declaratively minted secrets', () => {
        expect(secretExpiresLabel(null)).toBe('')
        expect(secretExpiresLabel(undefined)).toBe('')
        expect(secretExpiresLabel(1)).toBe('minted secrets live 1 day')
        expect(secretExpiresLabel(90)).toBe('minted secrets live 90 days')
    })
})
