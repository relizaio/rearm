import { describe, it, expect } from 'vitest'
import { hasOrgWrite, orgWriteSentence } from './orgWrite'

const ORG = 'org-1'
const row = (over: Record<string, unknown>) => ({ org: ORG, object: ORG, scope: 'ORGANIZATION', type: 'READ_WRITE', ...over })

/** FDAW-14 row 15. */
describe('hasOrgWrite', () => {
    it('is true for an ORGANIZATION READ_WRITE or ADMIN row of the organization', () => {
        expect(hasOrgWrite([row({})], ORG)).toBe(true)
        expect(hasOrgWrite([row({ type: 'ADMIN' })], ORG)).toBe(true)
    })

    it('is false for a COMPONENT READ_WRITE row only', () => {
        expect(hasOrgWrite([row({ scope: 'COMPONENT', object: 'comp-1' })], ORG)).toBe(false)
    })

    it('is false for another organization\'s ORGANIZATION row and for a READ_ONLY row', () => {
        expect(hasOrgWrite([row({ org: 'org-2', object: 'org-2' })], ORG)).toBe(false)
        expect(hasOrgWrite([row({ type: 'READ_ONLY' })], ORG)).toBe(false)
    })

    it('is false for empty and null permissions, and without an organization', () => {
        expect(hasOrgWrite([], ORG)).toBe(false)
        expect(hasOrgWrite(null, ORG)).toBe(false)
        expect(hasOrgWrite(undefined, ORG)).toBe(false)
        expect(hasOrgWrite([row({})], null)).toBe(false)
    })
})

describe('orgWriteSentence', () => {
    it('is the 3.9 string', () => {
        expect(orgWriteSentence('Attest all shown')).toBe('Attest all shown changes organization-wide data and needs'
            + ' organization write access, which an organization administrator grants in Organization Settings > Users')
    })
})
