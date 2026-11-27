import { describe, it, expect, vi } from 'vitest'
// submissionDrafts reaches the client through artifactDownload; the words need no client.
vi.mock('./fetchClient', () => ({ fetchWithAuth: vi.fn(), fetchArrayBufferWithAuth: vi.fn() }))
import { createHash } from 'crypto'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { CONFIRMATION_STATEMENT } from './submissionDrafts'

// FDAW-127: the change record of regulated outputs names the words of the confirmation statement by
// their sum (`confirmation-statement-text`). The words live here, in the UI; the record is generated in
// ReARM Pro, where no test can read them. This spec is what holds the two together: when it fails, the
// words changed or the copy of the record here is old. When the words change, the record follows in
// ReARM Pro first (before the first ReARM release that carries RO-1, in RO-1, in place; from that release
// on, in a new entry), then the page is copied here, and only then does this change pass. Whether the
// statement's version is raised with its words is not decided here.
// CI runs this spec on a push that touches ui/ or backend/, and on every push to main; a push that touches
// only documentation_site/ does not run it.
const RECORD = fileURLToPath(new URL('../../../documentation_site/docs/workflows/regulated-output-changes.md', import.meta.url))

export function recordedSum(record: string, identifier: string): string[] {
    return record.split('\n').filter(l => l.startsWith('| `' + identifier + '` | ')).map(l => l.split(' | ')[1])
}

describe('change record of regulated outputs', () => {
    it('names the words of the confirmation statement this UI shows', () => {
        const sums = recordedSum(readFileSync(RECORD, 'utf8'), 'confirmation-statement-text')
        expect(sums).toEqual([createHash('sha256').update(CONFIRMATION_STATEMENT, 'utf8').digest('hex')])
    })

    it('reads one row, the one of the identifier', () => {
        const rows = ['| `a-text` | 11 | x | y |', '| `a` | 22 | x | y |', 'text | `a` | 33 | x | y |'].join('\n')
        expect(recordedSum(rows, 'a')).toEqual(['22'])
        expect(recordedSum(rows, 'b')).toEqual([])
        expect(recordedSum(rows + '\n| `a` | 44 | x | y |', 'a')).toEqual(['22', '44'])
    })
})
