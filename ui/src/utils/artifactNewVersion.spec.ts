import { describe, it, expect } from 'vitest'
import { chosenUploadFile, hasLostStoredBom, artifactBelongsToForRow } from './artifactNewVersion'

/**
 * RUN, not scanned: these rules decide whether a "new version" can be submitted without a file
 * and which dialog a damaged artifact gets, and neither component is mounted in the suite.
 */
describe('the file chosen in the artifact form', () => {
    const f = new File(['{}'], 'sbom.json', { type: 'application/json' })

    it('is nothing before the user has picked anything (the ref still holds its initial array)', () => {
        expect(chosenUploadFile([])).toBeUndefined()
        expect(chosenUploadFile(undefined)).toBeUndefined()
    })

    it('is the picked file, whether the ref holds the change payload or the v-model list', () => {
        expect(chosenUploadFile({ file: { file: f }, fileList: [{ file: f }] })).toBe(f)
        expect(chosenUploadFile([{ file: f }])).toBe(f)
    })

    it('is nothing after the user removed it again, although the change payload still names it', () => {
        expect(chosenUploadFile({ file: { file: f, status: 'removed' }, fileList: [] })).toBeUndefined()
    })
})

describe('an artifact that has lost its stored BOM', () => {
    const lost = { type: 'BOM', internalBom: null, downloadLinks: [] }

    it('is a CycloneDX BOM with neither an internalBom nor download links', () => {
        expect(hasLostStoredBom(lost, true)).toBe(true)
        expect(hasLostStoredBom({ type: 'BOM' }, true)).toBe(true)
    })

    it('is not a healthy stored BOM', () => {
        expect(hasLostStoredBom({ ...lost, internalBom: { id: 'x', belongsTo: 'RELEASE' } }, true)).toBe(false)
    })

    it('is not an externally stored BOM, which never has an internalBom', () => {
        expect(hasLostStoredBom({ ...lost, downloadLinks: [{ uri: 'https://example.com/b.json' }] }, true)).toBe(false)
    })

    it('is not a CycloneDX VEX, VDR or attestation, which are stored without an internalBom', () => {
        for (const type of ['VEX', 'VDR', 'ATTESTATION']) expect(hasLostStoredBom({ ...lost, type }, true)).toBe(false)
    })

    it('is not a non-CycloneDX artifact', () => {
        expect(hasLostStoredBom(lost, false)).toBe(false)
    })
})

describe('what a release-page artifact row belongs to', () => {
    it('maps the rows of the release itself to the addArtifactManual values', () => {
        expect(artifactBelongsToForRow('Release')).toBe('RELEASE')
        expect(artifactBelongsToForRow('Source Code Entry')).toBe('SCE')
        expect(artifactBelongsToForRow('Deliverable')).toBe('DELIVERABLE')
    })

    it('is nothing for rows of other releases', () => {
        expect(artifactBelongsToForRow('Component: backend')).toBeUndefined()
        expect(artifactBelongsToForRow(undefined)).toBeUndefined()
    })
})
