import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { NOT_JSON_NOTE, NOT_SIGNED_NO_KEY, NOT_SIGNED_SIGNING_ERROR, SIGNED_LINE_PREFIX } from './sbomSigning'
import { IMPORT_HINT, REVOKE_CONFIRM, ROTATE_CONFIRM } from './sbomSigningKeys'

// T-28 (SCORE-35, design 3.8): the documentation page "Signing exported SBOMs" and its links. The
// docs build (cd documentation_site && npm ci && npm run docs:build) checks the links resolve;
// this pins what the design asks the page and its neighbours to say, with the UI's own texts.
const DOCS = fileURLToPath(new URL('../../../documentation_site/docs/', import.meta.url))
const doc = (f: string): string => readFileSync(DOCS + f, 'utf8')
const flat = (s: string): string => s.replace(/\s+/g, ' ')

const page = doc('workflows/signing-exported-sboms.md')

describe('documentation: Signing exported SBOMs', () => {
    it('is in the sidebar directly after Scoring an SBOM', () => {
        const config = doc('.vitepress/config.mjs')
        expect(config).toMatch(/\{text: 'Scoring an SBOM', link: '\/workflows\/sbom-scoring'\},\n\s*\{text: 'Signing exported SBOMs', link: '\/workflows\/signing-exported-sboms'\},/)
    })

    it('is in the workflows index after the scoring bullet', () => {
        expect(doc('workflows/index.md')).toMatch(/- \[Scoring an SBOM for CISA 2026 and FDA Readiness\]\(\.\/sbom-scoring\)\n- \[Signing exported SBOMs\]\(\.\/signing-exported-sboms\)\n/)
    })

    it('has its title and sections in the design order', () => {
        expect(page).toMatch(/^---[\s\S]*?---\n\n# Signing exported SBOMs\n/)
        const sections = [...page.matchAll(/^## (.+)$/gm)].map(m => m[1])
        expect(sections).toEqual(['What a signed export is', 'Configure', 'Rotate and revoke', 'Import', 'How a consumer verifies', 'What the UI shows', 'Limitations'])
    })

    it('gives the JWKS URL shape, the cache time and the three command lines', () => {
        expect(page).toContain('https://<your ReARM>/api/public/v1/organization/<organization uuid>/sbom-signing-keys')
        expect(page).toContain('max-age=300')
        expect(page).toContain('rearm bomutils verify-signature -f release.cdx.json --key org-sbom-keys.jwks.json')
        expect(page).toContain('rearm exportreleasebom --releaseid <release uuid> | rearm bomutils verify-signature --key pub.pem')
        expect(page).toContain('rearm bomutils score -f release.cdx.json --signature-key org-sbom-keys.jwks.json --fail-on-not-ready')
    })

    it('quotes the dialog lines, the confirms and the import hint word for word', () => {
        const text = flat(page)
        for (const line of [`${SIGNED_LINE_PREFIX}<key id>`, NOT_SIGNED_NO_KEY, NOT_SIGNED_SIGNING_ERROR, NOT_JSON_NOTE, ROTATE_CONFIRM.text, REVOKE_CONFIRM.text]) {
            expect(text).toContain(line)
        }
        expect(text).toContain('SBOM signing key import expects an Ed25519 private key as PKCS#8 PEM (BEGIN PRIVATE KEY)')
        expect(IMPORT_HINT).toContain('PKCS#8 PEM (BEGIN PRIVATE KEY)')
    })

    it('names every stamped property and reason, and every verification value', () => {
        for (const v of ['reliza:export:signed', 'reliza:export:signatureKeyId', 'reliza:export:unsignedReason', 'NO_KEY', 'SIGNING_ERROR',
            'NOT_REQUESTED', 'VERIFIED', 'FAILED', 'KEY_MISMATCH', 'KEY_REVOKED', 'UNSUPPORTED_ALGORITHM', 'UNSUPPORTED_FORM', 'sbom-author-signature']) {
            expect(page).toContain(`\`${v}\``)
        }
    })

    it('links back to the export and scoring pages, which link to it', () => {
        expect(page).toContain('](./exporting-compliance-artifacts)')
        expect(page).toContain('](./sbom-scoring)')

        const exporting = doc('workflows/exporting-compliance-artifacts.md')
        const scoring = exporting.indexOf('\n### Scoring the export\n')
        const signing = exporting.indexOf('\n### Signing the export\n')
        expect(scoring).toBeGreaterThan(-1)
        expect(signing).toBeGreaterThan(scoring)
        // The next heading after the scoring subsection is the signing one.
        expect(exporting.slice(scoring + 1).match(/\n#{2,3} .+/)?.[0]).toBe('\n### Signing the export')
        expect(exporting.slice(signing, exporting.indexOf('\n## ', signing))).toContain('](./signing-exported-sboms)')

        const notAssessed = flat(doc('workflows/sbom-scoring.md'))
        expect(notAssessed).toContain('the SBOM author signature is checked for presence and well-formedness; the server does not verify it'
            + ' (the CLI does, with `--signature-key`), see [Signing exported SBOMs](./signing-exported-sboms).')
        expect(notAssessed).not.toContain('checked for presence only; it is not verified')
    })
})
