import { describe, expect, it } from 'vitest'
import { canDeclareKey, canReleaseKeyName, DECLARABLE_KEY_TYPES, declaredNameProblem, declaredSourceDetail, declaredSourceLabel,
    declarePayload, MAX_DECLARED_NAME, releasePayload, secretExpiresLabel } from './apiKeyDeclaration'
import { tsFull } from './agentTaskFormat'

const key = (type: string, status: string = 'ACTIVE', declaredName: string | null = null) => ({ uuid: 'k1', type, status, declaredName })

describe('apiKeyDeclaration', () => {
    it('offers "Declare as…" to an org admin on a live key of a declarable type only', () => {
        expect(DECLARABLE_KEY_TYPES).toEqual(['FREEFORM', 'ORGANIZATION', 'ORGANIZATION_RW', 'COMPONENT'])
        for (const t of ['FREEFORM', 'ORGANIZATION', 'ORGANIZATION_RW', 'COMPONENT']) {
            expect(canDeclareKey(key(t, 'ACTIVE'), true)).toBe(true)
            expect(canDeclareKey(key(t, 'INACTIVE'), true)).toBe(true)
            expect(canDeclareKey(key(t, 'REQUESTED'), true)).toBe(false)
            expect(canDeclareKey(key(t, 'DENIED'), true)).toBe(false)
        }
        for (const t of ['USER', 'FEDERATED', 'INSTANCE', 'CLUSTER', 'REGISTRY_USER', 'APPROVAL']) {
            expect(canDeclareKey(key(t), true)).toBe(false)
        }
    })

    it('offers nothing to a viewer who is not an org admin', () => {
        expect(canDeclareKey(key('FREEFORM'), false)).toBe(false)
        expect(canDeclareKey(key('COMPONENT', 'ACTIVE', 'ci'), false)).toBe(false)
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
