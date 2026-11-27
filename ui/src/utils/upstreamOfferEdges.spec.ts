// T-1140 to T-1143 (TPF-243 design 3.4 to 3.6), mutation round: the edges of the decisions of the
// source's list that the first specs leave open, each pinned from both sides: the constants, the
// character range and the length of an identifier value, the token and the facts a page leaves in
// a set, the lines of the statement, the clean empty answer, the filter, the cut by code points,
// the expandable row and the words of a failed page. No mount.
import { describe, expect, it } from 'vitest'
import {
    OFFER_IDENTIFIERS_SHOWN, OFFER_NAME_SHOWN, OFFER_PAGE_SIZE, OFFER_TABLE_PAGE, OFFER_VALUE_SHOWN, applyOfferPage, cutForDisplay, emptyOfferSet,
    filterOfferRows, identifierInput, identifierProblem, isCleanEmpty, isCut, mergeOfferPage, offerFailureText, offerStatements, rowIsExpandable,
    tablePageOf,
} from './upstreamOffer'
import { GET_UPSTREAM_OFFERED_ITEMS, offeredPageOf } from './upstreamOfferQueries'
import type { OfferPage, OfferRow, OfferSet } from './upstreamOffer'
import {
    identifierValueProblem, offerCutSentence, offerEndSentence, offerFailureSentence, offerMissingHint, offerMoreSentence, offerTokenUnusableSentence,
} from './upstreamLabels'

const U = (n: number) => `dddddddd-0000-4000-8000-${String(n).padStart(12, '0')}`

function row (n: number, over: Partial<OfferRow> = {}): OfferRow {
    return { upstreamUuid: U(n), name: `Item ${n}`, identifiers: [], identifiersDropped: 0, activeFollow: null, ...over }
}

function page (over: Partial<OfferPage> = {}): OfferPage {
    return {
        source: null, kind: 'PRODUCT', failure: null, sourceFailure: null, message: null, items: [], next: 'NONE', nextPageToken: null,
        rowsReceived: 0, rowsDropped: 0, cut: 'NONE', ...over,
    }
}

const ids = (n: number, value = 'v') => Array.from({ length: n }, (_, i) => ({ idType: 'PURL', idValue: `${value}${i}` }))

describe('the constants of the list', () => {
    it('hasTheNumbersOfTheDesign', () => {
        expect(OFFER_PAGE_SIZE).toBe(100)
        expect(OFFER_TABLE_PAGE).toBe(25)
        expect(OFFER_IDENTIFIERS_SHOWN).toBe(3)
        expect(OFFER_NAME_SHOWN).toBe(120)
        expect(OFFER_VALUE_SHOWN).toBe(100)
    })
})

describe('the identifier value, edge by edge', () => {
    it('acceptsTheLastPrintableCharacterAndRefusesTheOnesBesideTheRange', () => {
        expect(identifierProblem('TEI', '~')).toBe('')
        expect(identifierProblem('TEI', '\x7F')).toBe(identifierValueProblem)
        expect(identifierProblem('TEI', 'a\x1Fb')).toBe(identifierValueProblem)
        expect(identifierProblem('TEI', 'a b')).toBe('')
        expect(identifierProblem('TEI', '!')).toBe('')
    })

    it('countsTheLengthOfTheTrimmedValueNotOfTheTypedOne', () => {
        expect(identifierProblem('PURL', ' ' + 'a'.repeat(2048) + ' ')).toBe('')
        expect(identifierProblem('PURL', 'a'.repeat(2049))).toBe(identifierValueProblem)
        expect(identifierProblem('PURL', 'a')).toBe('')
        expect(identifierProblem('PURL', '')).toBe(identifierValueProblem)
        expect(identifierProblem('PURL', '   ')).toBe(identifierValueProblem)
        expect(identifierProblem('PURL', undefined)).toBe(identifierValueProblem)
        expect(identifierProblem('PURL', null)).toBe(identifierValueProblem)
    })

    it('refusesAnUnknownTypeEachOfTheFourIsKnown', () => {
        for (const type of ['CPE', 'TEI', 'PURL', 'COMPLIANCE_DOCUMENT']) expect(identifierProblem(type, 'x'), type).toBe('')
        for (const type of ['', 'cpe', 'purl ', 'OTHER', undefined, null]) expect(identifierProblem(type as any, 'x'), String(type)).not.toBe('')
    })

    it('givesNoInputForAProblemAndTheTrimmedInputOtherwise', () => {
        expect(identifierInput('PURL', '')).toBeNull()
        expect(identifierInput(null, 'x')).toBeNull()
        expect(identifierInput('PURL', '  pkg:a  ')).toEqual({ idType: 'PURL', idValue: 'pkg:a' })
        expect(identifierInput('PURL', '  pkg:a  ')).not.toBeNull()
    })
})

describe('what a page leaves in a set', () => {
    it('readsAnUndefinedNextAsNullAndNoItemsAsNoRows', () => {
        let set: OfferSet | null = null
        expect(() => { set = applyOfferPage(emptyOfferSet(), page({ next: undefined as any, items: undefined as any })) }).not.toThrow()
        expect(set!.next).toBeNull()
        expect(set!.rows).toEqual([])
    })

    it('keepsATokenOnlyForNextTokenAndANonEmptyText', () => {
        const withToken = (next: any, token: any) => applyOfferPage(emptyOfferSet(), page({ next, nextPageToken: token })).token
        expect(withToken('TOKEN', 'tok')).toBe('tok')
        expect(withToken('TOKEN', ' tok ')).toBe(' tok ')
        expect(withToken('TOKEN', '')).toBeNull()
        expect(withToken('TOKEN', null)).toBeNull()
        expect(withToken('TOKEN', 5)).toBeNull()
        expect(withToken('NONE', 'tok')).toBeNull()
        expect(withToken('TOKEN_UNUSABLE', 'tok')).toBeNull()
        expect(withToken(null, 'tok')).toBeNull()
    })

    it('keepsTheRowsReceivedOfTheLatestMaxRowsPageAndNotTheLargest', () => {
        const first = applyOfferPage(emptyOfferSet(), page({ cut: 'MAX_ROWS', rowsReceived: 1500 }))
        const second = applyOfferPage(first, page({ cut: 'MAX_ROWS', rowsReceived: 1200 }))
        expect(second.maxRowsReceived).toBe(1200)
        expect(applyOfferPage(second, page({ cut: 'NONE', rowsReceived: 7 })).maxRowsReceived).toBe(1200)
        expect(applyOfferPage(emptyOfferSet(), page({ cut: 'NONE', rowsReceived: 7 })).maxRowsReceived).toBeNull()
        expect(applyOfferPage(emptyOfferSet(), page({ cut: 'OTHER', rowsReceived: 7 })).maxRowsReceived).toBeNull()
    })

    it('readsAMaxRowsPageWithoutACountAsZeroRows', () => {
        const set = applyOfferPage(emptyOfferSet(), page({ cut: 'MAX_ROWS', rowsReceived: null }))
        expect(set.maxRowsReceived).toBe(0)
        const statements = offerStatements(set)
        expect(statements.warnings).toEqual(['The source answered 0 rows in one page, more than ReARM reads. Rows are missing.'])
        expect(statements.hint).toBe(offerMissingHint)
    })

    it('keepsTheOtherCutOnceAnyPageWasCutWithIt', () => {
        const cut = applyOfferPage(emptyOfferSet(), page({ cut: 'OTHER' }))
        expect(cut.cutOther).toBe(true)
        expect(applyOfferPage(cut, page({ cut: 'NONE' })).cutOther).toBe(true)
        expect(applyOfferPage(cut, page({ cut: 'MAX_ROWS', rowsReceived: 3 })).cutOther).toBe(true)
        expect(applyOfferPage(emptyOfferSet(), page({ cut: 'MAX_ROWS', rowsReceived: 3 })).cutOther).toBe(false)
        expect(applyOfferPage(emptyOfferSet(), page({ cut: 'NONE' })).cutOther).toBe(false)
    })

    it('sumsTheDroppedRowsAndCountsNoNegativeOrMissingNumber', () => {
        const one = applyOfferPage(emptyOfferSet(), page({ rowsDropped: 2 }))
        const two = applyOfferPage(one, page({ rowsDropped: 3 }))
        expect(two.rowsDropped).toBe(5)
        expect(applyOfferPage(two, page({ rowsDropped: null })).rowsDropped).toBe(5)
        expect(applyOfferPage(two, page({ rowsDropped: -4 })).rowsDropped).toBe(5)
        expect(applyOfferPage(emptyOfferSet(), page({ rowsDropped: undefined as any })).rowsDropped).toBe(0)
    })

    it('startsEmptyWithNoFactAtAll', () => {
        expect(emptyOfferSet()).toEqual({ rows: [], next: null, token: null, maxRowsReceived: null, cutOther: false, rowsDropped: 0 })
        expect(emptyOfferSet()).not.toBe(emptyOfferSet())
    })
})

describe('the statement, edge by edge', () => {
    const hand = (over: Partial<OfferSet>): OfferSet => ({ ...emptyOfferSet(), ...over })

    it('offersLoadMoreOnlyForNextTokenWithATokenWhateverTheTokenOfAnotherNext', () => {
        expect(offerStatements(hand({ next: 'NONE', token: 'tok' })).canLoadMore).toBe(false)
        expect(offerStatements(hand({ next: 'NONE', token: 'tok' })).second).toBe('END')
        expect(offerStatements(hand({ next: 'TOKEN_UNUSABLE', token: 'tok' })).canLoadMore).toBe(false)
        expect(offerStatements(hand({ next: 'TOKEN_UNUSABLE', token: 'tok' })).second).toBe('TOKEN_UNUSABLE')
        expect(offerStatements(hand({ next: null, token: 'tok' })).canLoadMore).toBe(false)
        expect(offerStatements(hand({ next: 'TOKEN', token: null })).canLoadMore).toBe(false)
        expect(offerStatements(hand({ next: 'TOKEN', token: '' })).canLoadMore).toBe(false)
        expect(offerStatements(hand({ next: 'TOKEN', token: 'tok' })).canLoadMore).toBe(true)
        expect(offerStatements(hand({ next: 'TOKEN', token: 'tok' })).secondText).toBe(offerMoreSentence)
    })

    it('readsASetWithNoNextAsMoreRowsThatCannotBeLoaded', () => {
        const s = offerStatements(emptyOfferSet())
        expect(s.second).toBe('TOKEN_UNUSABLE')
        expect(s.secondText).toBe(offerTokenUnusableSentence)
        expect(s.hint).toBe(offerMissingHint)
        expect(s.count).toBe('0 rows loaded.')
    })

    it('hasOneSentencePerSecondLineAndTheHintOnlyWhenRowsAreMissing', () => {
        expect(offerStatements(hand({ next: 'NONE' })).secondText).toBe(offerEndSentence)
        expect(offerStatements(hand({ next: 'NONE' })).hint).toBeNull()
        expect(offerStatements(hand({ next: 'TOKEN', token: 'tok' })).hint).toBeNull()
        expect(offerStatements(hand({ next: 'NONE', cutOther: true })).hint).toBe(offerMissingHint)
        expect(offerStatements(hand({ next: 'NONE', rowsDropped: 1 })).hint).toBe(offerMissingHint)
        expect(offerStatements(hand({ next: 'NONE', maxRowsReceived: 0 })).hint).toBe(offerMissingHint)
        expect(offerStatements(hand({ next: 'TOKEN_UNUSABLE' })).hint).toBe(offerMissingHint)
    })

    it('warnsForARowsReceivedOfZeroAndOnlyForAMaxRowsCut', () => {
        expect(offerStatements(hand({ next: 'NONE', maxRowsReceived: 0 })).warnings).toHaveLength(1)
        expect(offerStatements(hand({ next: 'NONE', maxRowsReceived: null })).warnings).toEqual([])
        expect(offerStatements(hand({ next: 'NONE', rowsDropped: 0 })).warnings).toEqual([])
        expect(offerStatements(hand({ next: 'NONE', rowsDropped: 1 })).warnings).toEqual(['1 row of the source was not usable and is not shown.'])
    })

    it('listsTheWarningsInTheOrderMaxRowsOtherDropped', () => {
        const all = offerStatements(hand({ next: 'NONE', maxRowsReceived: 9, cutOther: true, rowsDropped: 2 })).warnings
        expect(all).toEqual([
            'The source answered 9 rows in one page, more than ReARM reads. Rows are missing.',
            offerCutSentence,
            '2 rows of the source were not usable and are not shown.',
        ])
    })

    it('callsOnlyAnEmptyEndWithNoFactMissingCleanEmpty', () => {
        expect(isCleanEmpty(hand({ next: 'NONE' }))).toBe(true)
        expect(isCleanEmpty(hand({ next: 'NONE', rows: [row(1)] }))).toBe(false)
        expect(isCleanEmpty(hand({ next: 'NONE', cutOther: true }))).toBe(false)
        expect(isCleanEmpty(hand({ next: 'NONE', rowsDropped: 1 }))).toBe(false)
        expect(isCleanEmpty(hand({ next: 'NONE', maxRowsReceived: 0 }))).toBe(false)
        expect(isCleanEmpty(hand({ next: 'TOKEN', token: 'tok' }))).toBe(false)
        expect(isCleanEmpty(hand({ next: 'TOKEN_UNUSABLE' }))).toBe(false)
        expect(isCleanEmpty(hand({ next: null }))).toBe(false)
    })
})

describe('the filter, edge by edge', () => {
    const rows = [
        row(1, { name: 'Alpha', identifiers: [{ idType: 'PURL', idValue: 'pkg:npm/zeta' }] }),
        row(2, { name: null }),
        row(3, { name: 'Gamma', identifiers: [{ idType: 'CPE', idValue: 'cpe:2.3:a:acme' }] }),
    ]

    it('returnsACopyForABlankText', () => {
        for (const text of ['', '  ', null, undefined]) {
            const out = filterOfferRows(rows, text)
            expect(out).toEqual(rows)
            expect(out).not.toBe(rows)
        }
    })

    it('matchesTheNameTheUuidAndTheValueOfAnIdentifierEachOnItsOwnButNotItsType', () => {
        expect(filterOfferRows(rows, 'alph').map(r => r.upstreamUuid)).toEqual([U(1)])
        expect(filterOfferRows(rows, U(2).toUpperCase()).map(r => r.upstreamUuid)).toEqual([U(2)])
        expect(filterOfferRows(rows, 'ZETA').map(r => r.upstreamUuid)).toEqual([U(1)])
        expect(filterOfferRows(rows, 'purl')).toEqual([])
        expect(filterOfferRows(rows, 'cpe:2.3')).toEqual([rows[2]])
        expect(filterOfferRows(rows, '  gamma  ').map(r => r.upstreamUuid)).toEqual([U(3)])
    })

    it('keepsARowWithoutIdentifiersOutOfAMatchOnTheirText', () => {
        expect(filterOfferRows([row(4, { name: 'x', identifiers: undefined as any })], 'zzz')).toEqual([])
        expect(filterOfferRows([row(4, { name: 'x', identifiers: undefined as any })], 'x')).toHaveLength(1)
    })
})

describe('the cut and the expandable row, edge by edge', () => {
    const astral = '\u{1F600}'

    it('cutsAtTheMaximumOfCodePointsAndKeepsTheEllipsisThree', () => {
        expect(cutForDisplay('abcd', 4)).toBe('abcd')
        expect(cutForDisplay('abcde', 4)).toBe('abcd...')
        expect(cutForDisplay('abcde', 3)).toBe('abc...')
        expect(cutForDisplay('abcde', 5)).toBe('abcde')
        expect(cutForDisplay(astral.repeat(4), 3)).toBe(astral.repeat(3) + '...')
    })

    it('countsCodePointsToTellACutText', () => {
        expect(isCut(astral.repeat(120), 120)).toBe(false)
        expect(isCut(astral.repeat(121), 120)).toBe(true)
        expect(isCut('a'.repeat(120), 120)).toBe(false)
        expect(isCut('a'.repeat(121), 120)).toBe(true)
        expect(isCut(null, 0)).toBe(false)
        expect(isCut(undefined, 0)).toBe(false)
        expect(isCut('', 0)).toBe(false)
        expect(isCut('a', 0)).toBe(true)
    })

    it('expandsAtOneBeyondEachShownLimitAndNotAtTheLimit', () => {
        expect(rowIsExpandable(row(1, { name: 'n'.repeat(OFFER_NAME_SHOWN) }))).toBe(false)
        expect(rowIsExpandable(row(1, { name: 'n'.repeat(OFFER_NAME_SHOWN + 1) }))).toBe(true)
        expect(rowIsExpandable(row(1, { name: astral.repeat(OFFER_NAME_SHOWN) }))).toBe(false)
        expect(rowIsExpandable(row(1, { name: astral.repeat(OFFER_NAME_SHOWN + 1) }))).toBe(true)
        expect(rowIsExpandable(row(1, { identifiers: [{ idType: 'CPE', idValue: 'v'.repeat(OFFER_VALUE_SHOWN) }] }))).toBe(false)
        expect(rowIsExpandable(row(1, { identifiers: [{ idType: 'CPE', idValue: 'v'.repeat(OFFER_VALUE_SHOWN + 1) }] }))).toBe(true)
        expect(rowIsExpandable(row(1, { identifiers: ids(OFFER_IDENTIFIERS_SHOWN) }))).toBe(false)
        expect(rowIsExpandable(row(1, { identifiers: ids(OFFER_IDENTIFIERS_SHOWN + 1) }))).toBe(true)
        expect(rowIsExpandable(row(1, { identifiersDropped: 0 }))).toBe(false)
        expect(rowIsExpandable(row(1, { identifiersDropped: 1 }))).toBe(true)
        expect(rowIsExpandable(row(1, { identifiersDropped: 2 }))).toBe(true)
    })

    it('doesNotExpandARowWithNoNameNoIdentifiersAndNoCount', () => {
        expect(rowIsExpandable(row(1, { name: null }))).toBe(false)
        expect(rowIsExpandable(row(1, { identifiers: undefined as any, identifiersDropped: undefined as any }))).toBe(false)
        expect(rowIsExpandable(row(1, { identifiersDropped: null as any }))).toBe(false)
    })

    it('findsTheTablePageOfTheRowsAroundTheEdgesOfAPage', () => {
        const at = [[-5, 1], [-1, 1], [0, 1], [24, 1], [25, 2], [49, 2], [50, 3], [99, 4], [100, 5]]
        for (const [index, wanted] of at) expect(tablePageOf(index), String(index)).toBe(wanted)
    })
})

describe('the words of a failed page, edge by edge', () => {
    it('showsTheCopyForAMessageOfOnlySpacesAndTheMessageUntrimmedOtherwise', () => {
        expect(offerFailureText({ failure: 'LIST_NOT_SUPPORTED', sourceFailure: null, message: '   ' })).toBe(offerFailureSentence.LIST_NOT_SUPPORTED)
        expect(offerFailureText({ failure: 'LIST_NOT_SUPPORTED', sourceFailure: null, message: '' })).toBe(offerFailureSentence.LIST_NOT_SUPPORTED)
        expect(offerFailureText({ failure: 'LIST_NOT_SUPPORTED', sourceFailure: null, message: ' served ' })).toBe(' served ')
        expect(offerFailureText({ failure: 'LIST_NOT_SUPPORTED', sourceFailure: null, message: null })).toBe(offerFailureSentence.LIST_NOT_SUPPORTED)
    })

    it('readsTheSourceFailureByItsOwnFieldAndShowsTheServedTextForTheOthers', () => {
        expect(offerFailureText({ failure: 'SOURCE_FAILURE', sourceFailure: 'TIMED_OUT', message: 'slow' })).toBe('slow')
        expect(offerFailureText({ failure: 'SOURCE_FAILURE', sourceFailure: 'TIMED_OUT', message: null })).toBe('')
        expect(offerFailureText({ failure: 'SOURCE_FAILURE', sourceFailure: 'TIMED_OUT', message: '  ' })).toBe('')
        expect(offerFailureText({ failure: 'ANSWER_INVALID', sourceFailure: 'FAILED', message: null })).toBe(offerFailureSentence.ANSWER_INVALID)
        expect(offerFailureText({ failure: 'ANSWER_INVALID', sourceFailure: 'FETCHER_BUSY', message: 'm' })).toBe('m')
    })
})

describe('the words of the failures, letter by letter', () => {
    it('hasTheSevenSentencesOfTheBackendCopiedWord', () => {
        expect(offerFailureSentence).toEqual({
            TOKEN_NOT_ACCEPTED: 'The server did not accept the page token. Start again from the first page.',
            FILTER_REFUSED: 'The server refused the identifier lookup.',
            LIST_NOT_SUPPORTED: 'The server does not offer this list.',
            ANSWER_INVALID: 'The server\'s answer is not a TEA list.',
            ANSWER_TOO_LARGE: 'The server\'s answer is larger than ReARM reads. Look the item up by an identifier.',
            CREDENTIAL_UNREADABLE: 'The stored credential can no longer be read. Enter it again.',
            SOURCE_FAILURE: 'The source or the fetcher failed.',
        })
    })
})

describe('the inputs that are missing, answered without an exception', () => {
    it('refusesAMissingValueOrTypeWithItsProblemAndDoesNotThrow', () => {
        expect(() => identifierProblem('PURL', null)).not.toThrow()
        expect(() => identifierProblem('PURL', undefined)).not.toThrow()
        expect(identifierProblem('PURL', null)).toBe(identifierValueProblem)
        expect(identifierProblem('PURL', undefined)).toBe(identifierValueProblem)
        expect(identifierProblem(null, 'x')).not.toBe('')
        expect(identifierProblem(undefined, 'x')).not.toBe('')
        expect(identifierInput('PURL', null)).toBeNull()
    })

    it('mergesAMissingPageIntoTheLoadedRowsWithoutAnExceptionAndWithoutChangingThem', () => {
        const loaded = [row(1), row(2)]
        expect(() => mergeOfferPage(loaded, null)).not.toThrow()
        expect(() => mergeOfferPage(loaded, undefined)).not.toThrow()
        expect(mergeOfferPage(loaded, null)).toEqual(loaded)
        expect(mergeOfferPage(loaded, undefined)).toEqual(loaded)
        expect(mergeOfferPage(loaded, null)).not.toBe(loaded)
        expect(() => applyOfferPage(emptyOfferSet(), page({ items: null as any }))).not.toThrow()
        expect(applyOfferPage(emptyOfferSet(), page({ items: null as any })).rows).toEqual([])
    })

    it('namesTheOperationOfTheListLikeItsField', () => {
        const op: any = GET_UPSTREAM_OFFERED_ITEMS.definitions[0]
        expect(GET_UPSTREAM_OFFERED_ITEMS.definitions).toHaveLength(1)
        expect(op.operation).toBe('query')
        expect(op.name.value).toBe('upstreamOfferedItems')
    })

    it('readsAMissingAnswerOfTheListAsNoPageWithoutAnException', () => {
        const served = page({ items: [row(1)] })
        expect(() => offeredPageOf(null)).not.toThrow()
        expect(() => offeredPageOf(undefined)).not.toThrow()
        expect(offeredPageOf(null)).toBeNull()
        expect(offeredPageOf(undefined)).toBeNull()
        expect(offeredPageOf({})).toBeNull()
        expect(offeredPageOf({ upstreamOfferedItems: null })).toBeNull()
        expect(offeredPageOf({ upstreamOfferedItems: served })).toBe(served)
    })

    it('keepsEveryRowForABlankTextEvenOneThatHasNothingToMatch', () => {
        const bare = { upstreamUuid: null, name: null, identifiers: null, identifiersDropped: 0, activeFollow: null } as any
        const rows = [bare, row(1)]
        for (const text of ['', '   ', null, undefined]) {
            expect(filterOfferRows(rows, text)).toEqual(rows)
        }
        expect(filterOfferRows(rows, 'item')).toEqual([row(1)])
    })

    it('expandsOnlyForTheFourReasonsAndNeverForARowWithMissingFields', () => {
        const bare = { upstreamUuid: U(1), name: null, identifiers: null, identifiersDropped: null, activeFollow: null } as any
        expect(() => rowIsExpandable(bare)).not.toThrow()
        expect(rowIsExpandable(bare)).toBe(false)
        expect(rowIsExpandable({ ...bare, identifiers: undefined, identifiersDropped: undefined })).toBe(false)
    })
})
