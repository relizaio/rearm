// T-1140 to T-1143 (TPF-243 design 3.4 to 3.6): the decisions of the source's list in the start of
// a follow, without a mount: the identifier rule of a lookup, the merge of a page by uuid, the
// statement about missing rows, the filter over the loaded rows, and the cut for display.
import { describe, expect, it } from 'vitest'
import {
    OFFER_NAME_SHOWN, OFFER_VALUE_SHOWN, applyOfferPage, cutForDisplay, emptyOfferSet, filterOfferRows, identifierInput, identifierProblem,
    isCleanEmpty, mergeOfferPage, offerFailureText, offerStatements, rowIsExpandable, tablePageOf,
} from './upstreamOffer'
import type { OfferPage, OfferRow, OfferSet } from './upstreamOffer'
import {
    identifierValueProblem, offerCutSentence, offerEndSentence, offerFetcherBusy, offerLookupTypeProblem, offerMissingHint, offerMoreSentence,
    offerSourceFailed, offerTokenUnusableSentence,
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

function setOf (...pages: OfferPage[]): OfferSet {
    return pages.reduce((s, p) => applyOfferPage(s, p), emptyOfferSet())
}

describe('T-1140: the identifier rule of a lookup', () => {
    it('asksForATypeFirst', () => {
        expect(identifierProblem(null, 'pkg:npm/x')).toBe(offerLookupTypeProblem)
        expect(identifierProblem(undefined, 'pkg:npm/x')).toBe(offerLookupTypeProblem)
        expect(identifierProblem('NOT_A_TYPE', 'pkg:npm/x')).toBe(offerLookupTypeProblem)
        expect(identifierInput(null, 'pkg:npm/x')).toBeNull()
    })

    it('usesTheBackendSentenceLetterForLetter', () => {
        expect(identifierValueProblem).toBe('The identifier value must be 1 to 2048 printable ASCII characters.')
    })

    it.each([
        ['empty', ''],
        ['spaces only', '    '],
        ['2049 characters', 'a'.repeat(2049)],
        ['a line feed inside', 'pkg:npm/a\nb'],
        ['U+007F', 'pkg:npm/a' + String.fromCharCode(0x7f)],
        ['a non-ASCII letter', 'pkg:npm/' + String.fromCharCode(0xe9)],
        ['null', null],
    ])('refuses %s', (_what, value) => {
        expect(identifierProblem('PURL', value as any)).toBe(identifierValueProblem)
        expect(identifierInput('PURL', value as any)).toBeNull()
    })

    it.each([
        ['1 character', 'a'],
        ['2048 characters', 'a'.repeat(2048)],
        ['a space inside', 'cpe:2.3:a:acme:gate way'],
    ])('accepts %s', (_what, value) => {
        expect(identifierProblem('CPE', value)).toBe('')
        expect(identifierInput('CPE', value)).toEqual({ idType: 'CPE', idValue: value })
    })

    it('trimsThePaddedValueForTheRequest', () => {
        expect(identifierProblem('PURL', '  pkg:npm/acme@1.0.0 \t')).toBe('')
        expect(identifierInput('PURL', '  pkg:npm/acme@1.0.0 \t')).toEqual({ idType: 'PURL', idValue: 'pkg:npm/acme@1.0.0' })
    })

    it('acceptsEachOfTheFourTypes', () => {
        for (const type of ['CPE', 'TEI', 'PURL', 'COMPLIANCE_DOCUMENT']) expect(identifierProblem(type, 'x'), type).toBe('')
    })
})

describe('T-1141: the merge of a page into the loaded rows', () => {
    it('takesTheFirstPageInTheOrderServed', () => {
        expect(mergeOfferPage([], [row(2), row(1)]).map(r => r.upstreamUuid)).toEqual([U(2), U(1)])
    })

    it('appendsNewUuidsAndReplacesARepeatedOneInItsPlace', () => {
        const loaded = [row(1), row(2), row(3)]
        const items = [row(4), row(2, { name: 'Renamed' }), row(5)]
        const loadedCopy = JSON.parse(JSON.stringify(loaded))
        const itemsCopy = JSON.parse(JSON.stringify(items))
        const merged = mergeOfferPage(loaded, items)
        expect(merged.map(r => r.upstreamUuid)).toEqual([U(1), U(2), U(3), U(4), U(5)])
        expect(merged[1].name).toBe('Renamed')
        expect(merged).toHaveLength(5)
        expect(loaded).toEqual(loadedCopy)
        expect(items).toEqual(itemsCopy)
        expect(merged).not.toBe(loaded)
    })

    it('changesNothingForAnEmptyPage', () => {
        const loaded = [row(1), row(2)]
        expect(mergeOfferPage(loaded, [])).toEqual(loaded)
        expect(mergeOfferPage(loaded, null)).toEqual(loaded)
    })

    it('keepsOneRowForAUuidRepeatedInOnePage', () => {
        expect(mergeOfferPage([], [row(1), row(1, { name: 'Later' })])).toEqual([row(1, { name: 'Later' })])
    })
})

describe('T-1142: the statement of a loaded set', () => {
    it('saysTheEndForNextNone', () => {
        const s = offerStatements(setOf(page({ items: [row(1)], next: 'NONE' })))
        expect(s.count).toBe('1 row loaded.')
        expect(s.second).toBe('END')
        expect(s.secondText).toBe(offerEndSentence)
        expect(s.canLoadMore).toBe(false)
        expect(s.warnings).toEqual([])
        expect(s.hint).toBeNull()
    })

    it('offersLoadMoreForNextTokenWithAToken', () => {
        const s = offerStatements(setOf(page({ items: [row(1), row(2)], next: 'TOKEN', nextPageToken: 't1' })))
        expect(s.count).toBe('2 rows loaded.')
        expect(s.second).toBe('MORE')
        expect(s.secondText).toBe(offerMoreSentence)
        expect(s.canLoadMore).toBe(true)
        expect(s.hint).toBeNull()
    })

    it.each([
        ['TOKEN_UNUSABLE', page({ items: [row(1)], next: 'TOKEN_UNUSABLE' })],
        ['TOKEN with a null token', page({ items: [row(1)], next: 'TOKEN', nextPageToken: null })],
        ['TOKEN with an empty token', page({ items: [row(1)], next: 'TOKEN', nextPageToken: '' })],
    ])('readsAsRowsThatCannotBeLoaded: %s', (_what, p) => {
        const s = offerStatements(setOf(p))
        expect(s.second).toBe('TOKEN_UNUSABLE')
        expect(s.secondText).toBe(offerTokenUnusableSentence)
        expect(s.canLoadMore).toBe(false)
        expect(s.hint).toBe(offerMissingHint)
        expect(s.secondText).not.toBe(offerEndSentence)
    })

    it('warnsForEachCauseOfMissingRowsInTheOrderOfTheDesign', () => {
        const s = offerStatements(setOf(page({ items: [row(1)], cut: 'MAX_ROWS', rowsReceived: 1500, rowsDropped: 1 }),
            page({ items: [row(2)], cut: 'OTHER', rowsDropped: 3 })))
        expect(s.warnings).toEqual([
            'The source answered 1500 rows in one page, more than ReARM reads. Rows are missing.',
            offerCutSentence,
            '4 rows of the source were not usable and are not shown.',
        ])
        expect(s.second).toBe('END')
        expect(s.hint).toBe(offerMissingHint)
    })

    it('keepsTheWarningsOfAFirstPageAfterACleanSecondPage', () => {
        const s = offerStatements(setOf(
            page({ items: [row(1)], next: 'TOKEN', nextPageToken: 't1', cut: 'MAX_ROWS', rowsReceived: 1500, rowsDropped: 1 }),
            page({ items: [row(2)], next: 'NONE' })))
        expect(s.count).toBe('2 rows loaded.')
        expect(s.warnings).toEqual(['The source answered 1500 rows in one page, more than ReARM reads. Rows are missing.',
            '1 row of the source was not usable and is not shown.'])
        expect(s.hint).toBe(offerMissingHint)
    })

    it('saysOneOrSeveralDroppedRows', () => {
        expect(offerStatements(setOf(page({ rowsDropped: 1 }))).warnings).toEqual(['1 row of the source was not usable and is not shown.'])
        expect(offerStatements(setOf(page({ rowsDropped: 3 }))).warnings).toEqual(['3 rows of the source were not usable and are not shown.'])
    })

    it('keepsTheLatestRowsReceivedOfAMaxRowsCut', () => {
        const s = offerStatements(setOf(page({ cut: 'MAX_ROWS', rowsReceived: 1500, next: 'TOKEN', nextPageToken: 'a' }),
            page({ cut: 'MAX_ROWS', rowsReceived: 2500 })))
        expect(s.warnings).toEqual(['The source answered 2500 rows in one page, more than ReARM reads. Rows are missing.'])
    })

    it('keepsTheTokenOfTheLastPageOnly', () => {
        const set = setOf(page({ next: 'TOKEN', nextPageToken: 'a' }), page({ next: 'NONE', nextPageToken: 'stale' }))
        expect(set.token).toBeNull()
        expect(setOf(page({ next: 'TOKEN', nextPageToken: 'a' }), page({ next: 'TOKEN', nextPageToken: 'b' })).token).toBe('b')
    })

    it('callsOnlyTheCleanEmptyAnswerEmpty', () => {
        expect(isCleanEmpty(setOf(page()))).toBe(true)
        expect(isCleanEmpty(setOf(page({ rowsDropped: 1 })))).toBe(false)
        expect(isCleanEmpty(setOf(page({ next: 'TOKEN', nextPageToken: 'a' })))).toBe(false)
        expect(isCleanEmpty(setOf(page({ next: 'TOKEN_UNUSABLE' })))).toBe(false)
        expect(isCleanEmpty(setOf(page({ items: [row(1)] })))).toBe(false)
    })
})

describe('T-1143: the filter over the loaded rows', () => {
    const long = 'x'.repeat(OFFER_VALUE_SHOWN) + 'NEEDLE-IN-THE-CUT'
    const rows: OfferRow[] = [
        row(1, { name: null }),
        row(2, { name: 'Acme Gateway' }),
        row(3, { name: 'ACME agent' }),
        row(4, { name: 'Other', identifiers: [
            { idType: 'CPE', idValue: 'cpe:one' }, { idType: 'TEI', idValue: 'tei://two' }, { idType: 'PURL', idValue: 'pkg:npm/three' },
            { idType: 'PURL', idValue: 'pkg:npm/fourth-only' },
        ] }),
        row(5, { name: 'Long', identifiers: [{ idType: 'PURL', idValue: long }] }),
    ]

    it.each([[''], ['   '], [null]])('keepsEveryRowFor %s', (text) => {
        expect(filterOfferRows(rows, text as any)).toEqual(rows)
    })

    it('matchesPartOfANameInAnotherCase', () => {
        expect(filterOfferRows(rows, 'acme').map(r => r.upstreamUuid)).toEqual([U(2), U(3)])
    })

    it('matchesPartOfAUuid', () => {
        expect(filterOfferRows(rows, U(4).slice(-6).toUpperCase()).map(r => r.upstreamUuid)).toEqual([U(4)])
    })

    it('matchesAnIdentifierBeyondTheThirdAndTheCutPartOfAValue', () => {
        expect(filterOfferRows(rows, 'fourth-only').map(r => r.upstreamUuid)).toEqual([U(4)])
        expect(filterOfferRows(rows, 'needle-in').map(r => r.upstreamUuid)).toEqual([U(5)])
    })

    it('doesNotMatchTheWordsOfANullName', () => {
        expect(filterOfferRows(rows, '(no name)')).toEqual([])
    })

    it('findsNothingForATextNoRowHolds', () => {
        expect(filterOfferRows(rows, 'nothing holds this')).toEqual([])
    })

    it('keepsTheOrderAndDoesNotChangeTheInput', () => {
        const before = rows.slice()
        const out = filterOfferRows(rows, 'e')
        expect(out.map(r => rows.indexOf(r))).toEqual([...out.map(r => rows.indexOf(r))].sort((a, b) => a - b))
        expect(rows).toEqual(before)
    })
})

describe('the cut for display and the expandable row', () => {
    it('cutsByCodePointsAndNeverHalfACharacter', () => {
        const smile = String.fromCodePoint(0x1f600)
        expect(cutForDisplay(smile.repeat(5), 3)).toBe(smile.repeat(3) + '...')
        expect(cutForDisplay('abc', 3)).toBe('abc')
        expect(cutForDisplay('abcd', 3)).toBe('abc...')
    })

    it('expandsOnlyARowThatShowsLessThanItHas', () => {
        expect(rowIsExpandable(row(1))).toBe(false)
        expect(rowIsExpandable(row(1, { name: 'n'.repeat(OFFER_NAME_SHOWN) }))).toBe(false)
        expect(rowIsExpandable(row(1, { name: 'n'.repeat(OFFER_NAME_SHOWN + 1) }))).toBe(true)
        expect(rowIsExpandable(row(1, { identifiers: [{ idType: 'CPE', idValue: 'v'.repeat(OFFER_VALUE_SHOWN + 1) }] }))).toBe(true)
        const four = [1, 2, 3, 4].map(i => ({ idType: 'CPE', idValue: `cpe:${i}` }))
        expect(rowIsExpandable(row(1, { identifiers: four.slice(0, 3) }))).toBe(false)
        expect(rowIsExpandable(row(1, { identifiers: four }))).toBe(true)
        expect(rowIsExpandable(row(1, { identifiersDropped: 1 }))).toBe(true)
    })

    it('findsTheTablePageOfARow', () => {
        expect(tablePageOf(0)).toBe(1)
        expect(tablePageOf(24)).toBe(1)
        expect(tablePageOf(25)).toBe(2)
        expect(tablePageOf(100)).toBe(5)
    })
})

describe('the words of a failed page', () => {
    it('showsTheServedMessageOrTheCopyOfTheBackendSentence', () => {
        expect(offerFailureText({ failure: 'LIST_NOT_SUPPORTED', sourceFailure: null, message: 'Served.' })).toBe('Served.')
        expect(offerFailureText({ failure: 'LIST_NOT_SUPPORTED', sourceFailure: null, message: null })).toBe('The server does not offer this list.')
        expect(offerFailureText({ failure: 'LIST_NOT_SUPPORTED', sourceFailure: null, message: '  ' })).toBe('The server does not offer this list.')
        expect(offerFailureText({ failure: 'NOT_A_FAILURE', sourceFailure: null, message: null })).toBe('')
    })

    it('replacesTheProbeWordsOfFetcherBusyAndFailed', () => {
        expect(offerFailureText({ failure: 'SOURCE_FAILURE', sourceFailure: 'FETCHER_BUSY', message: 'The fetcher was busy. Probe again later.' })).toBe(offerFetcherBusy)
        expect(offerFailureText({ failure: 'SOURCE_FAILURE', sourceFailure: 'FAILED', message: 'The probe failed.' })).toBe(offerSourceFailed)
        expect(offerFailureText({ failure: 'SOURCE_FAILURE', sourceFailure: 'TIMED_OUT', message: 'The server did not answer in time.' }))
            .toBe('The server did not answer in time.')
        expect(offerFailureText({ failure: 'SOURCE_FAILURE', sourceFailure: 'TIMED_OUT', message: null })).toBe('')
    })
})
