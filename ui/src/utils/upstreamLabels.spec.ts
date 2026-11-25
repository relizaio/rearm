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
