import { describe, expect, it } from 'vitest'
import {
    DESCRIPTION_MAX, TITLE_MAX, taskDescriptionProblem, taskRegisterInput, taskTitleProblem,
    boardFieldOfError, derivedTaskPrefix, documentsDraftOf, documentsInput, documentsPatch, documentsRootPlaceholder,
    normaliseTaskPrefix, priorTaskPrefixes, slug, taskPrefixPatch, taskPrefixPlaceholder, taskPrefixProblem,
} from './agentBoardNaming'

// The board form's naming fields (task fceb1e57): what the form previews and what a save sends.
describe('the task-key prefix field', () => {
    it('derives the placeholder the way the server does, for a two-word and a one-word name', () => {
        expect(derivedTaskPrefix('ReARM Dogfood')).toBe('RD')
        expect(derivedTaskPrefix('payments service core')).toBe('PSC')
        expect(derivedTaskPrefix('platform')).toBe('PL')
        expect(derivedTaskPrefix('x')).toBe('XB')
        expect(derivedTaskPrefix('')).toBe('BB')
        expect(derivedTaskPrefix('a b c d e f g h i j')).toBe('ABCDEFGH')
        expect(taskPrefixPlaceholder('ReARM Dogfood')).toBe('RD (from the name; a digit is appended if taken)')
    })

    it('normalises as the server does and says when the shape is wrong', () => {
        expect(normaliseTaskPrefix(' rd2 ')).toBe('RD2')
        expect(taskPrefixProblem('rd')).toBeNull()
        expect(taskPrefixProblem('')).toBeNull()
        for (const bad of ['R', 'TOOLONGPREFIX', 'R-D']) expect(taskPrefixProblem(bad), bad).not.toBeNull()
    })

    it('is sent only when set and changed, upper-cased', () => {
        expect(taskPrefixPatch(null, '')).toBeUndefined()
        expect(taskPrefixPatch(null, 'qx')).toBe('QX')
        expect(taskPrefixPatch({ taskPrefix: 'RD' }, 'rd')).toBeUndefined()
        expect(taskPrefixPatch({ taskPrefix: 'RD' }, 'R2')).toBe('R2')
        expect(taskPrefixPatch({ taskPrefix: 'RD' }, ' ')).toBeUndefined()
    })

    it('lists the prefixes held before the current one', () => {
        expect(priorTaskPrefixes(['RD', 'R2'], 'R2')).toEqual(['RD'])
        expect(priorTaskPrefixes(['RD', 'R2'], 'RD')).toEqual(['R2'])
        expect(priorTaskPrefixes(null, 'RD')).toEqual([])
    })
})

describe('the documents block', () => {
    it('reads a board into the draft, telling a set empty root from none', () => {
        expect(documentsDraftOf(null)).toEqual({ prefix: '', shared: false, rootSet: false, root: '' })
        expect(documentsDraftOf({ documents: { prefix: 'pay', shared: true, root: null } }))
            .toEqual({ prefix: 'pay', shared: true, rootSet: false, root: '' })
        expect(documentsDraftOf({ documents: { prefix: null, shared: false, root: '' } }))
            .toEqual({ prefix: '', shared: false, rootSet: true, root: '' })
    })

    it('sends the whole block when any member changed, an explicit empty root as ""', () => {
        const board = { documents: { prefix: null, shared: true, root: null } }
        const draft = documentsDraftOf(board)
        expect(documentsPatch(board, draft)).toBeUndefined()
        expect(documentsPatch(board, { ...draft, rootSet: true, root: '' })).toEqual({ prefix: null, shared: true, root: '' })
        expect(documentsPatch(board, { ...draft, prefix: ' pay ' })).toEqual({ prefix: 'pay', shared: true, root: null })
        expect(documentsPatch(board, { ...draft, shared: false })).toEqual({ prefix: null, shared: false, root: null })
        expect(documentsPatch({ documents: { root: '' } }, { ...documentsDraftOf({ documents: { root: '' } }), rootSet: false }))
            .toEqual({ prefix: null, shared: false, root: null })
    })

    it('a new board sends it only when something was set', () => {
        expect(documentsPatch(null, documentsDraftOf(null))).toBeUndefined()
        expect(documentsPatch(null, { prefix: '', shared: false, rootSet: true, root: '' })).toEqual({ prefix: null, shared: false, root: '' })
        expect(documentsInput({ prefix: '', shared: true, rootSet: true, root: ' team/{board} ' }))
            .toEqual({ prefix: null, shared: true, root: 'team/{board}' })
    })

    it('shows the default root as the placeholder and the name slug as the prefix one', () => {
        expect(documentsRootPlaceholder(true)).toContain('boards/{board}/')
        expect(documentsRootPlaceholder(false)).toContain('repository root')
        expect(slug('ReARM Dogfood')).toBe('rearm-dogfood')
        // The server's board slug: an accented letter is a separator (T-4 of tests/fceb1e57/run-1.md).
        expect(slug('Café Crème')).toBe('caf-cr-me')
        expect(slug('Tf Ünïcödé Straße Øre mujm01q1')).toBe('tf-n-c-d-stra-e-re-mujm01q1')
        expect(slug('  --Payments!! ')).toBe('payments')
    })
})

describe('a save refusal', () => {
    it('goes beside the field it is about', () => {
        expect(boardFieldOfError('RD is used by board Payments since 2026-09-01; a task-key prefix is never reused in an organization'))
            .toBe('taskPrefix')
        expect(boardFieldOfError("taskPrefix 'R' must be 2 to 8 letters and digits, e.g. RD")).toBe('taskPrefix')
        expect(boardFieldOfError("documents.root '../x' climbs out of the repository: a root has no '..'")).toBe('documents')
        expect(boardFieldOfError("documents.prefix '--' names nothing: it needs a letter or a digit")).toBe('documents')
        expect(boardFieldOfError('Board name is required')).toBeNull()
        expect(boardFieldOfError(null)).toBeNull()
    })
})

// The New task form (T-3 of tests/fceb1e57/run-1.md): the server's title and description rules, said
// before the save, and the description sent with the title.
describe('the New task form', () => {
    it('takes a title of 120 characters and says why a longer one or two lines are refused', () => {
        expect(TITLE_MAX).toBe(120)
        expect(taskTitleProblem('x'.repeat(120))).toBeNull()
        expect(taskTitleProblem('  ' + 'x'.repeat(120) + '  ')).toBeNull()
        expect(taskTitleProblem('x'.repeat(121))).toBe('Titles are at most 120 characters (this one is 121); put the rest in the description')
        expect(taskTitleProblem('one\ntwo')).toBe('A title is one line; put the rest in the description')
        expect(taskTitleProblem('one\rtwo')).not.toBeNull()
        expect(taskTitleProblem('')).toBeNull()
    })

    it('takes a description of 4000 characters and not more', () => {
        expect(DESCRIPTION_MAX).toBe(4000)
        expect(taskDescriptionProblem('d'.repeat(4000))).toBeNull()
        expect(taskDescriptionProblem('d'.repeat(4001))).toBe('A description is at most 4000 characters (this one is 4001)')
        expect(taskDescriptionProblem(undefined)).toBeNull()
    })

    it('sends the description whole with the trimmed title, and none when blank', () => {
        const long = 'Why: briefs in titles.\n\n  What: a description.  '
        expect(taskRegisterInput({ title: '  Cap titles ', description: long, externalRef: ' #12 ', sourceUrl: '' }))
            .toEqual({ title: 'Cap titles', description: long, externalRef: '#12', sourceUrl: null })
        expect(taskRegisterInput({ title: 'T', description: '   ', externalRef: '', sourceUrl: '' }).description).toBeNull()
    })
})
