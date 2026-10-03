import { describe, it, expect } from 'vitest'
import { belongsToLabel, generatedArtifactRows, isSyntheticArtifact, syntheticArtifactsAvailable, syntheticHistoryText } from './generatedArtifacts'

describe('generatedArtifactRows', () => {
    it('answers the synthetic list only, in order, marked as generated for the release', () => {
        const details = [
            { uuid: 's-2', tags: [{ key: 'syntheticArtifact', value: 'true' }] },
            { uuid: 's-1' }
        ]

        const rows = generatedArtifactRows(details, 'rel-1')

        expect(rows.map(r => r.uuid)).toEqual(['s-2', 's-1'])
        expect(rows.every(r => r.belongsTo === 'Generated' && r.belongsToUUID === 'rel-1')).toBe(true)
        expect(rows[1].tags).toEqual([])
    })

    it('is empty when nothing was loaded (CE, or before the load answers)', () => {
        expect(generatedArtifactRows(undefined, 'rel-1')).toEqual([])
        expect(generatedArtifactRows(null, 'rel-1')).toEqual([])
        expect(generatedArtifactRows([], 'rel-1')).toEqual([])
    })

    it('does not change the release it reads', () => {
        const ad = { uuid: 's-1', tags: [] }
        generatedArtifactRows([ad], 'rel-1')
        expect(ad).toEqual({ uuid: 's-1', tags: [] })
    })
})

describe('isSyntheticArtifact', () => {
    it('is true for the system tag', () => {
        expect(isSyntheticArtifact({ tags: [{ key: 'syntheticArtifact', value: 'true' }] })).toBe(true)
    })

    it('is true for a SYNTHETIC internal BOM', () => {
        expect(isSyntheticArtifact({ tags: [], internalBom: { id: 'b', belongsTo: 'SYNTHETIC' } })).toBe(true)
    })

    it('is false otherwise', () => {
        expect(isSyntheticArtifact({ tags: [{ key: 'syntheticArtifact', value: 'false' }] })).toBe(false)
        expect(isSyntheticArtifact({ tags: [], internalBom: { id: 'b', belongsTo: 'RELEASE' } })).toBe(false)
        expect(isSyntheticArtifact({})).toBe(false)
        expect(isSyntheticArtifact(null)).toBe(false)
    })
})

describe('belongsToLabel', () => {
    it('maps the belongsTo values', () => {
        expect(belongsToLabel('RELEASE')).toBe('Release')
        expect(belongsToLabel('SCE')).toBe('Source Code Entry')
        expect(belongsToLabel('DELIVERABLE')).toBe('Deliverable')
        expect(belongsToLabel('SYNTHETIC')).toBe('Generated')
    })

    it('passes an unknown value or an existing label through', () => {
        expect(belongsToLabel('AGENT_SESSION')).toBe('AGENT_SESSION')
        expect(belongsToLabel('Release')).toBe('Release')
        expect(belongsToLabel(undefined)).toBe('')
    })
})

describe('syntheticHistoryText', () => {
    const details = [{ uuid: 's-1', type: 'BOM', displayIdentifier: 'aggregated.cdx.json' }]

    it('names a bound generated artifact by type and display id', () => {
        expect(syntheticHistoryText('s-1', details)).toBe('Generated artifact BOM aggregated.cdx.json')
    })

    it('falls back to the uuid once the artifact is no longer bound, or nothing was loaded', () => {
        expect(syntheticHistoryText('s-gone', details)).toBe('Generated artifact s-gone')
        expect(syntheticHistoryText('s-gone', [])).toBe('Generated artifact s-gone')
        expect(syntheticHistoryText('s-gone', undefined)).toBe('Generated artifact s-gone')
    })
})

describe('syntheticArtifactsAvailable', () => {
    // CE is pinned against the CE schema in generatedArtifactsSchemaDrift.spec.ts.
    it('is true on every licensed edition, and on an unloaded user as the rest of the UI does', () => {
        for (const t of ['SAAS', 'DEMO', 'MANAGED_SERVICE', undefined, null]) {
            expect(syntheticArtifactsAvailable(t)).toBe(true)
        }
    })
})
