import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { NOT_JSON_NOTE, NOT_SIGNED_NO_KEY, NOT_SIGNED_SIGNING_ERROR, SIGNED_LINE_PREFIX } from './sbomSigning'
import { IMPORT_HINT, PUBLIC_KEYS_NOTE, REVOKE_CONFIRM, ROTATE_CONFIRM } from './sbomSigningKeys'

// T-28 (SCORE-35, design 3.8): the documentation page "Signing exported SBOMs" and its links. The
// docs build (cd documentation_site && npm ci && npm run docs:build) checks the links resolve;
// this pins what the design asks the page and its neighbours to say, with the UI's own texts.
// While the organization's public keys are not published, the cases below also pin what the page says instead.
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

    it('gives the three command lines with one public key file', () => {
        expect(page).toContain('rearm bomutils verify-signature -f release.cdx.json --key pub.pem')
        expect(page).toContain('rearm exportreleasebom --releaseid <release uuid> | rearm bomutils verify-signature --key pub.pem')
        expect(page).toContain('rearm bomutils score -f release.cdx.json --signature-key pub.pem --fail-on-not-ready')
    })

    it('presents no public keys URL, no cache time and no JWKS file', () => {
        const text = flat(page)
        for (const gone of ['/api/public/', 'sbom-signing-keys', 'max-age', 'Cache-Control', 'curl', 'org-sbom-keys', '.jwks.json',
            'public JWKS URL', 'exact URL', 'JWKS is public', 'published keys', 'stay published', 'no API key']) {
            expect(text).not.toContain(gone)
        }
    })

    it('quotes the panel note that publishing the keys is not available yet', () => {
        expect(flat(page)).toContain(`> Experimental: ${PUBLIC_KEYS_NOTE}`)
    })

    it('says how a consumer gets a key and what the CLI cannot know', () => {
        const text = flat(page)
        for (const sentence of [
            'ReARM does not publish an organization\'s public keys today, at any URL.',
            'The table does not show their public keys.',
            'copy the current key\'s public key (PEM or JWK) and note its key id before you rotate',
            'a consumer learns of a revocation only from you',
            'The consumer must be sure that the file comes from the organization',
            'ReARM does not hand out a JWKS today.',
            'The PEM and the JWK the settings section shows carry none',
            'Only a check against a public key you received from the organization says who signed it.',
        ]) {
            expect(text).toContain(sentence)
        }
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
