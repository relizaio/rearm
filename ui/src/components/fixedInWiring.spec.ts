import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { withoutComments } from '@/utils/specText'

/**
 * Wiring guard for the fix version columns, the has-fix filter and the
 * details panel's affected versions. Scope is imports and bindings only (see
 * findingScoresWiring.spec.ts); the columns run in findingColumnsFilters.spec.ts
 * and the wording and grouping in fixedInDisplay.spec.ts.
 */
function source (file: string): string {
    return withoutComments(readFileSync(fileURLToPath(new URL(file, import.meta.url)), 'utf8'))
}

const importBlockOf = (code: string) => (code.match(/^import\s[^;]*?\sfrom\s+'[^']+'/gms) || []).join('\n')

function expectImportedAndUsed (code: string, name: string) {
    expect(code).toMatch(new RegExp(`\\b${name}\\b(?![^\\n]*from ')`))
    expect(importBlockOf(code)).toMatch(new RegExp(`\\b${name}\\b`))
}

describe('VulnerabilityModal fix version wiring', () => {
    const code = source('./VulnerabilityModal.vue')

    it.each(['hasFixedInFields', 'fixVersionOf'])('imports %s, which it uses', (name) => {
        expectImportedAndUsed(code, name)
    })

    it('shows the fix columns in both views only when the rows carry fix versions', () => {
        expect(code).toMatch(/const showFixedIn = computed\(\(\) => hasFixedInFields\(props\.data\)\)/)
        expect(code.match(/showFixedIn:\s*showFixedIn\.value/g)).toHaveLength(2)
    })

    it('offers the has-fix filter only with fix versions, and clears it on every open', () => {
        expect(code).toMatch(/<template v-if="showFixedIn">[\s\S]*?v-model:value="hasFixOnly"/)
        const onOpen = code.match(/watch\(\(\) => props\.show, \(open\) => \{[\s\S]*?\n\}\)/)?.[0] || ''
        expect(onOpen).toMatch(/hasFixOnly\.value = false/)
    })
})

describe('VulnerabilityDetailsModal affected versions wiring', () => {
    const code = source('./VulnerabilityDetailsModal.vue')

    it.each(['affectedPackagesOf', 'rangeBoundsText', 'Copy20Regular'])('imports %s, which it uses', (name) => {
        expectImportedAndUsed(code, name)
    })

    it('imports NIcon for the copy button\'s icon', () => {
        expect(code).toMatch(/<n-icon><Copy20Regular \/><\/n-icon>/)
        expect(importBlockOf(code)).toMatch(/\bNIcon\b/)
    })

    it('shows the section only when the backend served the ranges', () => {
        expect(code).toMatch(/v-if="record\.affectedRanges !== undefined" label="Affected versions"/)
    })

    it.each(['appliesToFinding', 'fixedInText', 'fixedInTitle'])('imports %s for the clicked finding, which it uses', (name) => {
        expectImportedAndUsed(code, name)
    })

    it('says what applies to the finding only when opened from one with a fix version', () => {
        expect(code).toMatch(/v-if="fixedIn && findingPurl" label="This finding"/)
    })
})

describe('VulnerabilityModal opens the details panel with the clicked finding', () => {
    const code = source('./VulnerabilityModal.vue')

    it('passes the row\'s package and fix version, and not for an alias', () => {
        expect(code).toMatch(/:finding-purl="vulnDetail\.purl"/)
        expect(code).toMatch(/:fixed-in="vulnDetail\.fixedIn"/)
        expect(code).toMatch(/vulnId === row\.id \? row : \{ severity: row\.severity, knownExploited: row\.knownExploited \}/)
    })
})
