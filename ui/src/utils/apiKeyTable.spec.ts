import { describe, expect, it } from 'vitest'
import { API_KEY_PAGE_SIZE, apiKeyPagination, fmtKeyDate, isSecretExpired, keyActivityLines, secretDetailLines, secretDetailsText, showsKeyType } from './apiKeyTable'

describe('apiKeyTable: type column', () => {
    it('hides the type on the single-type tables (Free Form, key requests, user keys)', () => {
        expect(showsKeyType('freeForm')).toBe(false)
        expect(showsKeyType('keyRequests')).toBe(false)
        expect(showsKeyType('user')).toBe(false)
    })
    it('keeps it on the scoped table, where it tells component, instance, cluster and org keys apart', () => {
        expect(showsKeyType('scoped')).toBe(true)
    })
})

describe('apiKeyTable: pagination', () => {
    it('pages client-side, 8 keys per page', () => {
        expect(API_KEY_PAGE_SIZE).toBe(8)
        expect(apiKeyPagination()).toEqual({ pageSize: 8 })
    })
    it('gives each table its own pager object', () => {
        expect(apiKeyPagination()).not.toBe(apiKeyPagination())
    })
})

describe('apiKeyTable: secret details', () => {
    const row = { createdDate: '2026-05-18, 9:36:21 p.m.' }
    const expires = '2026-10-06T13:55:00Z'

    it('lists created, last used and expires', () => {
        const sec = { slot: 2, createdDate: '2026-10-06T08:00:00Z', lastUsedDate: '2026-10-07T09:00:00Z', expiresDate: expires }
        expect(secretDetailLines(sec, row)).toEqual([
            { label: 'Created', value: '2026-10-06' },
            { label: 'Last used', value: '2026-10-07' },
            { label: 'Expires', value: fmtKeyDate(expires) }
        ])
        expect(secretDetailsText(sec, row)).toBe(`created 2026-10-06 · last used 2026-10-07 · expires ${fmtKeyDate(expires)}`)
    })
    it('says never for an unused secret and leaves expires out when none is set', () => {
        const sec = { slot: 2, createdDate: '2026-10-06T08:00:00Z', lastUsedDate: null, expiresDate: null }
        expect(secretDetailsText(sec, row)).toBe('created 2026-10-06 · last used never')
    })
    it('falls back to the key creation date for a legacy slot 1, n/a for another slot', () => {
        expect(secretDetailLines({ slot: 1 }, row)[0]).toEqual({ label: 'Created', value: '2026-05-18' })
        expect(secretDetailLines({ slot: 2 }, row)[0]).toEqual({ label: 'Created', value: 'n/a' })
    })
    it('formats an expiry to the minute in local time', () => {
        expect(fmtKeyDate(expires)).toMatch(/^\d{4}-\d{2}-\d{2}, \d{2}:\d{2}$/)
        const local = new Date(2026, 9, 6, 13, 55, 0)
        expect(fmtKeyDate(local.toISOString())).toBe('2026-10-06, 13:55')
        expect(fmtKeyDate(new Date(2026, 0, 2, 3, 4).toISOString())).toBe('2026-01-02, 03:04')
        expect(fmtKeyDate(null)).toBe('')
    })
    it('treats a secret past its expiry as expired', () => {
        const now = Date.parse('2026-10-08T00:00:00Z')
        expect(isSecretExpired({ expiresDate: '2026-10-07T00:00:00Z' }, now)).toBe(true)
        expect(isSecretExpired({ expiresDate: '2026-10-09T00:00:00Z' }, now)).toBe(false)
        expect(isSecretExpired({ expiresDate: null }, now)).toBe(false)
    })
})

describe('apiKeyTable: activity column', () => {
    it('stacks created, last accessed and updated by', () => {
        expect(keyActivityLines({ createdDate: '2026-05-18, 9:36:21 p.m.', accessDate: 'Never', updatedByName: 'Jane' })).toEqual([
            { label: 'Created', value: '2026-05-18, 9:36:21 p.m.' },
            { label: 'Last accessed', value: 'Never' },
            { label: 'Updated by', value: 'Jane' }
        ])
    })
    it('drops updated by when nobody is resolved', () => {
        expect(keyActivityLines({ createdDate: 'x', accessDate: 'y', updatedByName: '' }).map(l => l.label)).toEqual(['Created', 'Last accessed'])
    })
})
