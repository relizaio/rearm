import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { withoutComments } from '@/utils/specText'

/**
 * FDAW-153 T-11 (3.5): the release findings list (VulnerabilityModal) and the Finding Analysis page
 * (VulnerabilityAnalysis) show no visible "suppressed": they say closed and open, with the count naming findings
 * or analysis records. Only visible text is checked (text nodes with their {{ }} interpolations removed, and static
 * label / title values); bound attributes and interpolation contents are identifiers, which keep their names
 * (showSuppressed, suppressedCount, pdfIncludeSuppressed, ...). No vue-tsc runs on <script setup>, so the wiring
 * is pinned on the source.
 */
function source (file: string): string {
    return withoutComments(readFileSync(fileURLToPath(new URL(file, import.meta.url)), 'utf8'))
}

/** The component's <template> block: from the first <template> up to its <script>. */
function templateOf (code: string): string {
    const from = code.indexOf('<template>')
    const to = code.indexOf('<script', from)
    return code.substring(from, to < 0 ? code.length : to)
}

const OLD_FORMS: ReadonlyArray<[string, RegExp]> = [
    ['>Show Suppressed', />\s*Show Suppressed/i],
    ['>Show Unsuppressed', />\s*Show Unsuppressed/i],
    [' suppressed)', / suppressed\)/i],
    [' unsuppressed)', / unsuppressed\)/i],
    ['label="Include Suppressed"', /label="Include Suppressed"/i]
]

/** Every visible "suppressed" of a template text, as a list of problems; empty when there is none. */
function visibleSuppressedProblems (template: string): string[] {
    const problems: string[] = []
    for (const [name, re] of OLD_FORMS) if (re.test(template)) problems.push(`old form ${name}`)
    for (const m of template.matchAll(/>([^<]*)</g)) {
        const text = m[1].replace(/\{\{[\s\S]*?\}\}/g, '')
        if (/suppressed/i.test(text)) problems.push(`text node ${JSON.stringify(text.trim())}`)
    }
    for (const m of template.matchAll(/(?<![:\w@.-])(label|title)="([^"]*)"/g)) {
        if (/suppressed/i.test(m[2])) problems.push(`static ${m[1]} ${JSON.stringify(m[2])}`)
    }
    return problems
}

// VulnerabilityModal.vue:14-23 and :180 as they were at the base (rearm 5b386d48), before FDAW-153
const OLD_MODAL_LINES = [
    '                    <span>Show Suppressed:</span>',
    '                    <n-switch v-model:value="showSuppressed" />',
    '                    <n-text depth="3" style="font-size: 12px;">',
    '                        ({{ suppressedCount }} suppressed)',
    '                    </n-text>',
    '                    <n-divider vertical />',
    '                    <span>Show Unsuppressed:</span>',
    '                    <n-switch v-model:value="showUnsuppressed" />',
    '                    <n-text depth="3" style="font-size: 12px;">',
    '                        ({{ unsuppressedCount }} unsuppressed)',
    '            <n-form-item label="Include Suppressed">'
].join('\n')

describe('closed and open words on the two finding lists (FDAW-153 3.5)', () => {
    const modal = templateOf(source('./VulnerabilityModal.vue'))
    const page = templateOf(source('./VulnerabilityAnalysis.vue'))

    it('the check fails on the template text before the change (it cannot pass vacuously)', () => {
        const problems = visibleSuppressedProblems(OLD_MODAL_LINES)
        for (const [name] of OLD_FORMS) expect(problems).toContain(`old form ${name}`)
        expect(problems.some(p => p.startsWith('text node'))).toBe(true)
        expect(problems.some(p => p.startsWith('static label'))).toBe(true)
    })

    it('the release findings list shows no visible suppressed', () => {
        expect(modal.length).toBeGreaterThan(0)
        expect(visibleSuppressedProblems(modal)).toEqual([])
    })

    it('the Finding Analysis page shows no visible suppressed', () => {
        expect(page.length).toBeGreaterThan(0)
        expect(visibleSuppressedProblems(page)).toEqual([])
    })

    it('the release findings list binds the words, counts findings and labels the export switch', () => {
        expect(modal).toMatch(/<span :title="CLOSED_FINDING_WORDS\.closedHelp">\{\{ CLOSED_FINDING_WORDS\.showClosed \}\}:<\/span>/)
        expect(modal).toMatch(/<span :title="CLOSED_FINDING_WORDS\.openHelp">\{\{ CLOSED_FINDING_WORDS\.showOpen \}\}:<\/span>/)
        expect(modal).toContain("{{ closedCountText(suppressedCount, 'finding') }}")
        expect(modal).toContain("{{ openCountText(unsuppressedCount, 'finding') }}")
        expect(modal).toContain(':label="CLOSED_FINDING_WORDS.includeClosed"')
        expect(modal).toContain('v-model:value="pdfIncludeSuppressed"')
    })

    it('the Finding Analysis page binds the words and counts records', () => {
        expect(page).toMatch(/<span :title="CLOSED_FINDING_WORDS\.closedHelp">\{\{ CLOSED_FINDING_WORDS\.showClosed \}\}:<\/span>/)
        expect(page).toMatch(/<span style="margin-left: 40px;" :title="CLOSED_FINDING_WORDS\.openHelp">\{\{ CLOSED_FINDING_WORDS\.showOpen \}\}:<\/span>/)
        expect(page).toContain("{{ closedCountText(suppressedCount, 'record') }}")
        expect(page).toContain("{{ openCountText(unsuppressedCount, 'record') }}")
    })

    it('both components import the words from findingAnalysisLabels', () => {
        for (const file of ['./VulnerabilityModal.vue', './VulnerabilityAnalysis.vue']) {
            const code = source(file)
            expect(code).toMatch(/^import \{[^}]*\bCLOSED_FINDING_WORDS\b[^}]*\bclosedCountText\b[^}]*\bopenCountText\b[^}]*\} from '@\/utils\/findingAnalysisLabels'/m)
        }
    })
})
