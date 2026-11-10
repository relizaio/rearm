import { describe, expect, it } from 'vitest'
import { customerCreateSteps, customerKeyRows, customerKeysTitle, customerUpdateInput, grantRows } from './teaCustomers'

// FDAW-40 row 22a (FDAW-25 3.12, FDAW-40 3.5): the pure parts of the Customers panel.

const KEYS = [
    { uuid: 'k1', holderName: 'Zeta', status: 'ACTIVE' },
    { uuid: 'k2', holderName: 'Acme', status: 'ACTIVE' },
]
const ORG = 'o1'
const ORG_READ = { org: ORG, scope: 'ORGANIZATION', object: ORG, type: 'READ_ONLY', functions: ['TEA_READ'] }
const STORED = {
    uuid: 'c1', name: 'Acme', description: 'd', status: 'ACTIVE', kind: 'CUSTOMER', apiKeys: ['k1', 'k2'],
    permissions: { permissions: [ORG_READ] },
}
const FORM = { name: 'Acme', description: 'd', apiKeys: ['k1', 'k2'] }
// the same grant as the editor sends it (permissionsFromScopedModel)
const ORG_READ_SCOPED = { scope: 'ORGANIZATION', objectId: ORG, type: 'READ_ONLY', functions: [], approvals: [] }

describe('customerKeyRows (row 22a)', () => {
    it('lists every key by holder name with the members marked', () => {
        const rows = customerKeyRows(KEYS, { apiKeys: ['k2'] })
        expect(rows.map(r => [r.uuid, r.holderName, r.member, r.label])).toEqual([
            ['k2', 'Acme', true, 'Acme (k2)'],
            ['k1', 'Zeta', false, 'Zeta (k1)'],
        ])
    })

    it('adds a checked row, labelled by its uuid, for a member the key list does not hold', () => {
        const rows = customerKeyRows(KEYS, { apiKeys: ['k2', 'kx'] })
        expect(rows).toHaveLength(3)
        expect(rows[2]).toEqual({ uuid: 'kx', holderName: '', status: null, member: true, label: 'kx' })
    })

    it('leaves every key unchecked without a group', () => {
        expect(customerKeyRows(KEYS, null).map(r => r.member)).toEqual([false, false])
    })
})

describe('customerKeysTitle (row 22a)', () => {
    it('joins the member holders sorted', () => {
        expect(customerKeysTitle(KEYS, { apiKeys: ['k1', 'k2'] })).toBe('Acme, Zeta')
    })

    it('names an unknown member by its uuid', () => {
        expect(customerKeysTitle(KEYS, { apiKeys: ['kx'] })).toBe('kx')
    })

    it('answers "" for no member', () => {
        expect(customerKeysTitle(KEYS, { apiKeys: [] })).toBe('')
    })
})

describe('grantRows (row 22a)', () => {
    it('compares a stored row and a scoped-model row of the same scope, object and type as equal', () => {
        expect(grantRows([ORG_READ])).toEqual(grantRows([ORG_READ_SCOPED]))
    })

    it('ignores functions', () => {
        expect(grantRows([{ ...ORG_READ, functions: ['TEA_READ'] }])).toEqual(grantRows([{ ...ORG_READ, functions: [] }]))
    })

    it('tells a different type apart', () => {
        expect(grantRows([ORG_READ])).not.toEqual(grantRows([{ ...ORG_READ, type: 'NONE' }]))
    })
})

describe('customerUpdateInput (row 22a)', () => {
    it('sends a changed name only, trimmed', () => {
        expect(customerUpdateInput(STORED, { ...FORM, name: '  New  ' }, [ORG_READ_SCOPED])).toEqual({ groupId: 'c1', name: 'New' })
    })

    it('sends a changed description only', () => {
        expect(customerUpdateInput(STORED, { ...FORM, description: 'e' }, [ORG_READ_SCOPED])).toEqual({ groupId: 'c1', description: 'e' })
    })

    it('sends "" for a cleared description', () => {
        expect(customerUpdateInput(STORED, { ...FORM, description: '' }, [ORG_READ_SCOPED])).toEqual({ groupId: 'c1', description: '' })
    })

    it('reads a stored null description as "" (no change)', () => {
        expect(customerUpdateInput({ ...STORED, description: null }, { ...FORM, description: '' }, [ORG_READ_SCOPED])).toBeNull()
    })

    it('sends the whole checked list when only the keys changed', () => {
        expect(customerUpdateInput(STORED, { ...FORM, apiKeys: ['k1'] }, [ORG_READ_SCOPED])).toEqual({ groupId: 'c1', apiKeys: ['k1'] })
    })

    it('sends the whole grant list when only the grants changed', () => {
        const comp = { scope: 'COMPONENT', objectId: 'c9', type: 'READ_ONLY', functions: [], approvals: [] }
        expect(customerUpdateInput(STORED, FORM, [ORG_READ_SCOPED, comp]))
            .toEqual({ groupId: 'c1', permissions: [ORG_READ_SCOPED, comp] })
    })

    it('sends [] for cleared keys, never null or absent', () => {
        const input = customerUpdateInput(STORED, { ...FORM, apiKeys: [] }, [ORG_READ_SCOPED])
        expect(input).toEqual({ groupId: 'c1', apiKeys: [] })
        expect(Array.isArray(input.apiKeys)).toBe(true)
    })

    it('sends [] for cleared grants, never null or absent', () => {
        const input = customerUpdateInput(STORED, FORM, [])
        expect(input).toEqual({ groupId: 'c1', permissions: [] })
        expect(Array.isArray(input.permissions)).toBe(true)
    })

    it('reads a re-ordered equal key set as no change', () => {
        expect(customerUpdateInput(STORED, { ...FORM, apiKeys: ['k2', 'k1'] }, [ORG_READ_SCOPED])).toBeNull()
    })

    it('reads a name differing only by surrounding spaces as no change', () => {
        expect(customerUpdateInput(STORED, { ...FORM, name: ' Acme ' }, [ORG_READ_SCOPED])).toBeNull()
    })

    it('reads a description differing only by surrounding spaces as no change', () => {
        expect(customerUpdateInput(STORED, { ...FORM, description: ' d ' }, [ORG_READ_SCOPED])).toBeNull()
    })

    it('answers null when nothing changed', () => {
        expect(customerUpdateInput(STORED, FORM, [ORG_READ_SCOPED])).toBeNull()
    })
})

describe('customerCreateSteps (row 22a)', () => {
    it('creates a CUSTOMER with the name trimmed and a blank description as null', () => {
        expect(customerCreateSteps({ name: '  Acme  ', description: '   ', apiKeys: [] }, [], [], ORG).create)
            .toEqual({ org: ORG, name: 'Acme', description: null, kind: 'CUSTOMER' })
    })

    it('follows with an update when there is a key or a grant, not otherwise', () => {
        const form = { name: 'Acme', description: '', apiKeys: [] }
        expect(customerCreateSteps(form, ['k1'], [], ORG).follow).toBe(true)
        expect(customerCreateSteps(form, [], [ORG_READ_SCOPED], ORG).follow).toBe(true)
        expect(customerCreateSteps(form, [], [], ORG).follow).toBe(false)
    })
})

// FDAW-40 mutation round: cells the surviving mutants showed missing.
describe('customerKeyRows labels and status (FDAW-40 mutation)', () => {
    it('labels a key with a blank holder by its uuid alone, and reads a missing status as null', () => {
        expect(customerKeyRows([{ uuid: 'k3', holderName: '' }], null))
            .toEqual([{ uuid: 'k3', holderName: '', status: null, member: false, label: 'k3' }])
    })
})

describe('customerUpdateInput with a swapped member or grant (FDAW-40 mutation)', () => {
    it('sends the keys when one member is swapped for another, the count unchanged', () => {
        expect(customerUpdateInput(STORED, { ...FORM, apiKeys: ['k1', 'k9'] }, [ORG_READ_SCOPED]))
            .toEqual({ groupId: 'c1', apiKeys: ['k1', 'k9'] })
    })

    it('sends the grants when one grant is swapped for another, the count unchanged', () => {
        const comp = { scope: 'COMPONENT', objectId: 'c9', type: 'READ_ONLY', functions: [], approvals: [] }
        expect(customerUpdateInput(STORED, FORM, [comp])).toEqual({ groupId: 'c1', permissions: [comp] })
    })
})
