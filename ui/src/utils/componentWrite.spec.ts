import { describe, it, expect } from 'vitest'
import { componentPageWritable } from './componentWrite'

const ORG = 'org-1'
const C = 'comp-1'
const P = 'persp-1'
const row = (scope: string, object: string, type: string, org: string = ORG) => ({ org, scope, object, type })
const READER = row('ORGANIZATION', ORG, 'READ_ONLY')

describe('componentPageWritable (FDAW-66)', () => {
    it('org write: an organization READ_WRITE or ADMIN row of this organization', () => {
        expect(componentPageWritable([row('ORGANIZATION', ORG, 'READ_WRITE')], ORG, null, C)).toBe(true)
        expect(componentPageWritable([row('ORGANIZATION', ORG, 'ADMIN')], ORG, null, C)).toBe(true)
        expect(componentPageWritable([row('ORGANIZATION', 'org-2', 'ADMIN', 'org-2')], ORG, null, C)).toBe(false)
    })

    it('perspective READ_WRITE on the current non-default perspective', () => {
        const rows = [READER, row('PERSPECTIVE', P, 'READ_WRITE')]
        expect(componentPageWritable(rows, ORG, P, C)).toBe(true)
        expect(componentPageWritable(rows, ORG, 'default', C)).toBe(false)
        expect(componentPageWritable(rows, ORG, null, C)).toBe(false)
        expect(componentPageWritable(rows, ORG, 'persp-2', C)).toBe(false)
    })

    it('component READ_WRITE on this component', () => {
        const rows = [READER, row('COMPONENT', C, 'READ_WRITE')]
        expect(componentPageWritable(rows, ORG, null, C)).toBe(true)
        expect(componentPageWritable(rows, ORG, null, 'comp-2')).toBe(false)
    })

    it('component ADMIN counts as write (the fix)', () => {
        expect(componentPageWritable([READER, row('COMPONENT', C, 'ADMIN')], ORG, null, C)).toBe(true)
        expect(componentPageWritable([READER, row('COMPONENT', C, 'ADMIN')], ORG, null, 'comp-2')).toBe(false)
        expect(componentPageWritable([READER, row('COMPONENT', C, 'ADMIN', 'org-2')], ORG, null, C)).toBe(false)
    })

    it('perspective ADMIN counts as write', () => {
        expect(componentPageWritable([READER, row('PERSPECTIVE', P, 'ADMIN')], ORG, P, C)).toBe(true)
        expect(componentPageWritable([READER, row('PERSPECTIVE', P, 'ADMIN', 'org-2')], ORG, P, null)).toBe(false)
    })

    it('tests every row, not the first match', () => {
        expect(componentPageWritable([READER, row('COMPONENT', C, 'READ_ONLY'), row('COMPONENT', C, 'ADMIN')],
            ORG, null, C)).toBe(true)
        expect(componentPageWritable([READER, row('PERSPECTIVE', P, 'READ_ONLY'), row('PERSPECTIVE', P, 'ADMIN')],
            ORG, P, null)).toBe(true)
    })

    it('read-only, essential-read, no rows and no organization are not write', () => {
        expect(componentPageWritable([READER], ORG, null, C)).toBe(false)
        expect(componentPageWritable([READER, row('COMPONENT', C, 'READ_ONLY')], ORG, null, C)).toBe(false)
        expect(componentPageWritable([READER, row('COMPONENT', C, 'ESSENTIAL_READ')], ORG, null, C)).toBe(false)
        expect(componentPageWritable([READER, row('PERSPECTIVE', P, 'READ_ONLY')], ORG, P, C)).toBe(false)
        expect(componentPageWritable(null, ORG, null, C)).toBe(false)
        expect(componentPageWritable([], ORG, null, C)).toBe(false)
        expect(componentPageWritable([row('COMPONENT', C, 'ADMIN')], null, null, C)).toBe(false)
    })
})
