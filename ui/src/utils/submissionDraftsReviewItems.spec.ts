import { describe, it, expect, vi } from 'vitest'
vi.mock('./fetchClient', () => ({ fetchWithAuth: vi.fn(), fetchArrayBufferWithAuth: vi.fn() }))
import { readinessRows, READINESS_ITEMS_TRUNCATED } from './submissionDrafts'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'

/** FDAW-161 3.8: an open REVIEW row lists its items on the draft card; a MUST_FIX or passed row lists none. */
const CONFLICT = { rule: 'SUPPORT_CLAIM_CONFLICT', severity: 'REVIEW', outcome: 'FAIL', count: 2, justified: null,
    text: '2 components say actively maintained with the end-of-support reason "community project, no support commitment";'
        + ' check each project and correct the level or the reason (1 entered in bulk)', verb: 'ASSESS_SUPPORT',
    items: [{ key: 'pkg:npm/a@1', label: 'a 1', detail: 'entered in bulk' }, { key: 'pkg:npm/b@2', label: 'b 2', detail: null }],
    itemsTruncated: false }

const rows = (r: any) => readinessRows({ readiness: { rows: [r] } }, { writable: true, canWriteOrg: true })

describe('readinessRows lists the items of an open REVIEW row', () => {
    it('label, and the detail in parentheses', () => {
        const [v] = rows(CONFLICT)
        expect(v.items).toEqual(['a 1 (entered in bulk)', 'b 2'])
        expect(v.itemsTruncated).toBe(false)
        expect(v.tag).toBe('Review')
    })

    it('a truncated list says so, with the fixed text', () => {
        expect(rows({ ...CONFLICT, itemsTruncated: true })[0].itemsTruncated).toBe(true)
        expect(READINESS_ITEMS_TRUNCATED).toBe('and more; the row\'s count is the total')
    })

    it('a passed REVIEW row, a MUST_FIX row and a row without items list none', () => {
        expect(rows({ ...CONFLICT, outcome: 'PASS' })[0].items).toEqual([])
        expect(rows({ ...CONFLICT, severity: 'MUST_FIX', itemsTruncated: true })[0]).toMatchObject({ items: [],
            itemsTruncated: false })
        expect(rows({ ...CONFLICT, items: undefined })[0].items).toEqual([])
    })

    it('the drafts query reads the items', () => {
        const q = readFileSync(fileURLToPath(new URL('./submissionQueries.ts', import.meta.url)), 'utf8')
        expect(q).toContain('readiness { rows { rule severity outcome count justified text verb items { key label detail }'
            + ' itemsTruncated } }')
    })
})
