// FDAW-122 row 5: the Download Log's label for every download type the two schemas serve, and the view's
// one query, which the CE server must still answer: it selects no field the CE schema lacks.
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { parse, validate } from 'graphql'
import { ceSchema, enumValuesOf, proSchema } from './schemaDriftSupport'
import { DOWNLOAD_TYPE_LABELS, downloadTypeLabel } from './downloadLog'

const view = readFileSync(resolve(__dirname, '../components/DownloadLogView.vue'), 'utf8')

describe('downloadLog (FDAW-122)', () => {
    it('(a) the three package types and the two older exports have a label; an unknown type shows its name', () => {
        expect(downloadTypeLabel('PACKAGE_ZIP_DOWNLOAD')).toBe('Package Zip Download')
        expect(downloadTypeLabel('RECORD_OF_REVIEW_EXPORT')).toBe('Record of Review Export')
        expect(downloadTypeLabel('DRAFT_ZIP_DOWNLOAD')).toBe('Draft Zip Download')
        expect(downloadTypeLabel('VEX_EXPORT')).toBe('VEX Export')
        expect(downloadTypeLabel('CLE_EXPORT')).toBe('CLE Export')
        expect(downloadTypeLabel('SBOM_EXPORT')).toBe('SBOM Export')
        expect(downloadTypeLabel('NEW_TYPE')).toBe('NEW_TYPE')
        for (const odd of ['constructor', 'toString', '__proto__', 'hasOwnProperty']) expect(downloadTypeLabel(odd), odd).toBe(odd)
        expect(downloadTypeLabel(null)).toBe('')
        expect(downloadTypeLabel(undefined)).toBe('')
        for (const label of Object.values(DOWNLOAD_TYPE_LABELS)) expect(label).not.toMatch(/_/)
    })

    it('(b) every DownloadType of the CE schema has a label', () => {
        for (const v of enumValuesOf(ceSchema, 'DownloadType')) expect(Object.keys(DOWNLOAD_TYPE_LABELS), v).toContain(v)
    })

    it.runIf(proSchema)('(c) the labels are exactly the DownloadType values of the Pro schema', () => {
        expect(Object.keys(DOWNLOAD_TYPE_LABELS).sort()).toEqual(enumValuesOf(proSchema!, 'DownloadType').sort())
    })

    it('(d) the view asks the label of the helper and holds no label of its own', () => {
        expect(view).toContain("import { downloadTypeLabel } from '@/utils/downloadLog'")
        expect(view).toContain('return downloadTypeLabel(type)')
        expect(view).not.toMatch(/case '[A-Z_]+_(EXPORT|DOWNLOAD)': return/)
    })

    it('(e) the view has one query, and the CE schema answers it', () => {
        const docs = [...view.matchAll(/gql`([\s\S]*?)`/g)].map(m => m[1])
        expect(docs).toHaveLength(1)
        expect(docs[0]).toContain('listDownloadLogs(org: $org')
        expect(validate(ceSchema, parse(docs[0])).map(e => e.message)).toEqual([])
        // the control: the same query with a field only the Pro schema has is refused by the CE schema
        const withProField = docs[0].replace('artifactVersion', 'artifactVersion submissionVersion')
        expect(withProField).not.toBe(docs[0])
        expect(validate(ceSchema, parse(withProField)).map(e => e.message)).toEqual(['Cannot query field "submissionVersion" on type "DownloadConfig".'])
    })

    it.runIf(proSchema)('(f) the Pro schema answers the view query too', () => {
        const doc = [...view.matchAll(/gql`([\s\S]*?)`/g)][0][1]
        expect(validate(proSchema!, parse(doc)).map(e => e.message)).toEqual([])
    })
})
