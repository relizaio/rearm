// T-501 (TPF-185 design 3.14): every label table of the upstream sources feature against the enum
// it is keyed by in the Pro schema, and the sentences that carry a decision, written out here.
import { describe, expect, it } from 'vitest'
import type { GraphQLSchema } from 'graphql'
import * as labels from './upstreamLabels'
import { UPSTREAM_LABEL_TABLES, runStateLabel, syncStateLabel } from './upstreamLabels'
import { enumValuesOf, proSchema } from './schemaDriftSupport'

const pairs = UPSTREAM_LABEL_TABLES.map(([name, table]) => [name, table] as [string, Record<string, string>])

describe('upstream label tables vs the Pro schema', () => {
    it.runIf(proSchema).each(pairs)('%s: the keys are exactly the values of the enum', (name, table) => {
        expect(proSchema!.getType(name), `${name} is not in the Pro schema`).toBeTruthy()
        expect(Object.keys(table).sort()).toEqual(enumValuesOf(proSchema as GraphQLSchema, name).sort())
    })
})

describe('upstream label tables', () => {
    it.each(pairs)('%s: no value is empty', (_name, table) => {
        for (const [key, value] of Object.entries(table)) {
            expect(typeof value, key).toBe('string')
            expect(value.trim(), key).not.toBe('')
        }
    })

    it('listsEveryExportedTable', () => {
        const listed = new Set(UPSTREAM_LABEL_TABLES.map(([, table]) => table))
        const exportedTables = Object.entries(labels)
            .filter(([, v]) => v !== null && typeof v === 'object' && !Array.isArray(v))
        expect(exportedTables.length).toBeGreaterThan(0)
        for (const [exportName, table] of exportedTables) {
            expect(listed.has(table as Record<string, string>), `${exportName} is not in UPSTREAM_LABEL_TABLES`).toBe(true)
        }
    })

    it('hasSixRunStatesAndFourSyncStatesInTheSameWords', () => {
        expect(Object.keys(runStateLabel)).toEqual(['QUEUED', 'RUNNING', 'COMPLETED', 'COMPLETED_WITH_FAILURES', 'STOPPED', 'FAILED'])
        expect(Object.keys(syncStateLabel)).toEqual(['RUNNING', 'COMPLETED', 'COMPLETED_WITH_FAILURES', 'FAILED'])
        for (const key of Object.keys(syncStateLabel)) expect(syncStateLabel[key]).toBe(runStateLabel[key])
    })
})

describe('upstream sentences that carry a decision', () => {
    it('areTheWordsOfTheDesign', () => {
        expect(labels.driftStopSentence).toBe('Stop: a release that does not fit the version lines is held and listed as pending until you reconfigure the follow. Releases that fit, and new documents of releases already ingested, keep coming in.')
        expect(labels.driftAdaptSentence).toBe('Adapt: ReARM works out a new depth from the release history and uses it from then on; releases already ingested keep their lines. When the history gives no clear answer, the release is held as under Stop. Either way an alert tells the administrators.')
        expect(labels.probeTimeoutHint).toBe('If the server is on a private network, the network policy of this installation may block its address. Whoever installed ReARM with helm can allow it.')
        expect(labels.probeApiKeyHint).toBe('Check the server root as well as the key: a wrong root gives the same answer.')
        expect(labels.startingPointRequired).toBe('Choose a starting point.')
        expect(labels.oneOffFailureSentence.NO_MATCHING_SOURCE).toBe('The TEI resolved to a server that is not an upstream source of the organization.')
        expect(labels.forbiddenSentence).toBe('You are not allowed to see this, or it does not exist. Upstream sources need read access to the whole organization.')
    })

    it('putsTheNameIntoTheArchiveAndUnfollowStatementsUnchanged', () => {
        const name = '<img src=x onerror=alert(1)> & "R&D"'
        expect(labels.archiveSourceStatement(name)).toBe(`Archive ${name}? ReARM stops syncing its follows and deletes the stored credential. Everything ingested from it stays and is marked as no longer updated. An archived source cannot be brought back; the same server can be added again as a new source.`)
        expect(labels.unfollowStatement(name)).toBe(`Unfollow ${name}? Nothing that was ingested is changed or deleted; it is marked as no longer updated. This cannot be undone. Following the item again needs a new confirmation, and its depth then stands as it is.`)
    })

    it('labelOfNeverReturnsTheRawValue', () => {
        expect(labels.labelOf(runStateLabel, 'QUEUED')).toBe('Queued')
        expect(labels.labelOf(runStateLabel, 'NOT_A_STATE')).toBe('')
        expect(labels.labelOf(runStateLabel, 'toString')).toBe('')
        expect(labels.labelOf(runStateLabel, null)).toBe('')
    })
})

// T-1162 (TPF-243 design 3.8): the words of the source's list in the start of a follow.
describe('the words of the source list in the start of a follow', () => {
    it('areTheWordsOfTheDesign', () => {
        expect(labels.offerModeListLabel).toBe('From the list')
        expect(labels.offerModeUuidLabel).toBe('By uuid')
        expect(labels.followUuidHint).toBe('The uuid of the product or component on the supplier\'s TEA server, for an item that is not in the list. Ask the supplier for it, or ingest one release by its TEI and use Follow this item on the product.')
        expect(labels.offerChooseSource).toBe('Choose a source to see its list.')
        expect(labels.offerSourceArchived).toBe('The source is archived. Its list is not read.')
        expect(labels.offerNeverProbed).toBe('The source was never probed. Probe it to read its list.')
        expect(labels.offerProbePending).toBe('The list is read when the probe has answered.')
        expect(labels.offerProbeFailed).toBe('The list is not read from a source whose last probe failed. Probe again, or enter the uuid of the item.')
        expect(labels.offerForbiddenSentence).toBe('You are not allowed to see the list of this source, or the source does not exist. The list needs an administrator of the organization.')
        expect(labels.offerLoading).toBe('Reading the list from the source. An answer can take up to about a minute.')
        expect(labels.offerWaiting).toBe('Waiting for the earlier request to the source to end.')
        expect(labels.offerEmptySentence).toEqual({ PRODUCT: 'The source lists no products.', COMPONENT: 'The source lists no components.' })
        expect(labels.offerEmptyHint).toBe('Switch the kind, or enter the uuid of the item.')
        expect(labels.offerNoName).toBe('(no name)')
        expect(labels.offerEndSentence).toBe('The source has no more rows.')
        expect(labels.offerMoreSentence).toBe('The source has more rows.')
        expect(labels.offerTokenUnusableSentence).toBe('The source has more rows, but its page token is not usable, so they cannot be loaded.')
        expect(labels.offerCutSentence).toBe('The answer of the source was cut. Rows are missing.')
        expect(labels.offerMissingHint).toBe('Look the item up by an identifier, or enter its uuid.')
        expect(labels.offerFilterPlaceholder).toBe('Filter the loaded rows')
        expect(labels.offerLookupTypeProblem).toBe('Choose an identifier type.')
        expect(labels.identifierValueProblem).toBe('The identifier value must be 1 to 2048 printable ASCII characters.')
        expect(labels.offerLookupHead).toBe('Result of the lookup.')
        expect(labels.offerLookupNoneSentence).toEqual({
            PRODUCT: 'The source has no product with this identifier.', COMPONENT: 'The source has no component with this identifier.',
        })
        expect(labels.offerLookupExactHint).toBe('The lookup is exact: the type and the whole value must match.')
        expect(labels.offerFollowed).toBe('Followed')
        expect(labels.offerFetcherBusy).toBe('The fetcher was busy. Try again later.')
        expect(labels.offerSourceFailed).toBe('The request to the source failed.')
        expect(labels.identifierTypeLabel).toEqual({ CPE: 'CPE', TEI: 'TEI', PURL: 'PURL', COMPLIANCE_DOCUMENT: 'Compliance document' })
    })

    it('countsInTheWordsOfTheDesign', () => {
        expect([0, 1, 2].map(labels.offerMoreIdentifiers)).toEqual(['and 0 more', 'and 1 more', 'and 2 more'])
        expect([0, 1, 2].map(labels.offerIdentifiersDropped)).toEqual([
            '0 identifiers of the source were not usable and are not shown.',
            '1 identifier of the source was not usable and is not shown.',
            '2 identifiers of the source were not usable and are not shown.',
        ])
        expect([0, 1, 2].map(labels.offerLoadedCount)).toEqual(['0 rows loaded.', '1 row loaded.', '2 rows loaded.'])
        expect([0, 1, 2, 1500].map(labels.offerMaxRowsSentence)).toEqual([0, 1, 2, 1500].map(r =>
            `The source answered ${r} rows in one page, more than ReARM reads. Rows are missing.`))
        expect([0, 1, 2].map(labels.offerRowsDroppedSentence)).toEqual([
            '0 rows of the source were not usable and are not shown.',
            '1 row of the source was not usable and is not shown.',
            '2 rows of the source were not usable and are not shown.',
        ])
        expect([0, 1, 2, 40].map(labels.offerFilterSentence)).toEqual([0, 1, 2, 40].map(n =>
            `Filters the rows loaded so far (${n}), by name, uuid and identifier. It does not search the source.`))
        expect(labels.offerFilterSentence(40)).toContain('It does not search the source.')
        expect(labels.offerFilterCount(0, 0)).toBe('Matching: 0 of 0 loaded rows.')
        expect(labels.offerFilterCount(1, 2)).toBe('Matching: 1 of 2 loaded rows.')
        expect(labels.offerFilterCount(3, 40)).toBe('Matching: 3 of 40 loaded rows.')
    })

    it('listsTheNewTablesUnderTheirEnums', () => {
        const listed = UPSTREAM_LABEL_TABLES.map(([name, table]) => [name, table])
        expect(listed).toContainEqual(['UpstreamIdentifierType', labels.identifierTypeLabel])
        expect(listed).toContainEqual(['UpstreamItemKind', labels.offerEmptySentence])
        expect(listed).toContainEqual(['UpstreamItemKind', labels.offerLookupNoneSentence])
        expect(listed).toContainEqual(['UpstreamOfferFailure', labels.offerFailureSentence])
    })
})
