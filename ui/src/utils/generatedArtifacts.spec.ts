import { describe, it, expect } from 'vitest'
import { belongsToLabel, generatedArtifactRows, isSyntheticArtifact, syntheticHistoryText } from './generatedArtifacts'

describe('generatedArtifactRows', () => {
    it('answers the synthetic list only, in order, marked as generated for the release', () => {
        const release = {
            uuid: 'rel-1',
            artifactDetails: [{ uuid: 'inventory', tags: [] }],
            syntheticArtifactDetails: [
                { uuid: 's-2', tags: [{ key: 'syntheticArtifact', value: 'true' }] },
                { uuid: 's-1' }
            ]
        }

        const rows = generatedArtifactRows(release)

        expect(rows.map(r => r.uuid)).toEqual(['s-2', 's-1'])
        expect(rows.every(r => r.belongsTo === 'Generated' && r.belongsToUUID === 'rel-1')).toBe(true)
        expect(rows[1].tags).toEqual([])
    })

    it('is empty for a release without the field or without a release', () => {
        expect(generatedArtifactRows({ uuid: 'rel-1', artifactDetails: [{ uuid: 'a' }] })).toEqual([])
        expect(generatedArtifactRows({ uuid: 'rel-1', syntheticArtifactDetails: null })).toEqual([])
        expect(generatedArtifactRows(undefined)).toEqual([])
    })

    it('does not change the release it reads', () => {
        const ad = { uuid: 's-1', tags: [] }
        generatedArtifactRows({ uuid: 'rel-1', syntheticArtifactDetails: [ad] })
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
    const release = { syntheticArtifactDetails: [{ uuid: 's-1', type: 'BOM', displayIdentifier: 'aggregated.cdx.json' }] }

    it('names a bound generated artifact by type and display id', () => {
        expect(syntheticHistoryText('s-1', release)).toBe('Generated artifact BOM aggregated.cdx.json')
    })

    it('falls back to the uuid once the artifact is no longer bound', () => {
        expect(syntheticHistoryText('s-gone', release)).toBe('Generated artifact s-gone')
        expect(syntheticHistoryText('s-gone', {})).toBe('Generated artifact s-gone')
    })
})
