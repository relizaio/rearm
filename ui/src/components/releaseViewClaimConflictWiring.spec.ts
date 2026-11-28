import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'

/**
 * FDAW-161 test 15, the seam: the single form shows the claim warning and Save does not read it; the bulk dialog's
 * Review selection reads bulkErrors, which validateBulkInput fills with the pair's refusal (supportClaimConflict.spec).
 */
const source = readFileSync(fileURLToPath(new URL('./ReleaseView.vue', import.meta.url)), 'utf8')
const code = source
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/<!--[\s\S]*?-->/g, '')
    .split('\n')
    .map(l => l.replace(/(^|[^:])\/\/.*$/, '$1'))
    .join('\n')

describe('the support claim conflict is wired into ReleaseView', () => {
    it('imports the warning text', () => {
        expect(source).toMatch(/import\s+\{[^}]*\bSUPPORT_CLAIM_CONFLICT_WARNING\b[^}]*\}\s+from\s+'@\/utils\/supportAttestationInput'/)
    })

    it('shows the warning alert while the form contradicts', () => {
        const alert = code.match(/<n-alert v-if="attestForm\.claimConflict\(\)" type="warning"[\s\S]*?<\/n-alert>/)
        expect(alert, 'the warning alert').not.toBeNull()
        expect(alert![0]).toContain('data-testid="attest-claim-conflict"')
        expect(alert![0]).toContain('{{ SUPPORT_CLAIM_CONFLICT_WARNING }}')
    })

    it('Save stays enabled: its disabled binding does not read the conflict', () => {
        const save = code.match(/:disabled="[^"]*!attestForm\.canSubmit\(\)"/)
        expect(save, 'the Save button binding').not.toBeNull()
        expect(save![0]).not.toContain('claimConflict')
    })

    it('Review selection is disabled by bulkErrors, which carries validateBulkInput', () => {
        expect(code).toMatch(/:disabled="bulkErrors\.length > 0 \|\| bulk\.collecting\.value/)
        expect(code).toMatch(/const bulkErrors[^=]*= computed\(\s*\(\): string\[\] => \[[^\]]*validateBulkInput\(bulkForm\)\]\)/)
    })
})
