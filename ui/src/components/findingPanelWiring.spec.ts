import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync, statSync } from 'fs'
import { join, relative } from 'path'
import { fileURLToPath } from 'url'

/**
 * Wiring guard for the vulnerability details panel hosts. Scope is deliberately
 * narrow (see releaseViewCoverageImports.spec.ts for why a source scan must not
 * pretend to test behaviour): the behaviour lives in findingIdLink.spec.ts and
 * useVulnerabilityDetail.spec.ts, where it runs.
 *
 * What only a scan can check, because this repo has no vue-tsc and no DOM test
 * environment:
 * - each host imports what its <script setup> uses (a missing import is a
 *   runtime ReferenceError that vite build and vitest both pass);
 * - each host renders the panel bound to the composable's state;
 * - every changelog FindingListSection forwards vuln-click, or its ids open nothing;
 * - no view has grown its own finding-link builder again.
 */
const srcDir = fileURLToPath(new URL('..', import.meta.url))

function code (relPath: string): string {
    return readFileSync(join(srcDir, relPath), 'utf8')
        .replace(/<!--[\s\S]*?-->/g, '')
        .replace(/\/\*[\s\S]*?\*\//g, '')
        .replace(/(^|[^:])\/\/.*$/gm, '$1')
}

/** Every .vue / .ts source under src, specs excluded, as paths relative to src. */
function sources (): string[] {
    const out: string[] = []
    const walk = (dir: string) => {
        for (const entry of readdirSync(dir)) {
            const path = join(dir, entry)
            if (statSync(path).isDirectory()) { walk(path); continue }
            if (/\.(vue|ts)$/.test(entry) && !entry.endsWith('.spec.ts')) out.push(relative(srcDir, path))
        }
    }
    walk(srcDir)
    return out
}

/** The import statements only, multi-line braces included. */
function imports (source: string): string {
    return (source.match(/^import\s[^;]*?\sfrom\s+'[^']+'/gms) || []).join('\n')
}

// Found, not listed: a new parent must forward vuln-click, or its ids open nothing.
const LIST_SECTION_PARENTS = sources().filter(f => f.endsWith('.vue')
    && /import FindingListSection from/.test(code(f)))

const PANEL_HOSTS = [
    'components/VulnerabilityModal.vue',
    'components/VulnerabilityAnalysis.vue',
    'components/ReleasesByCve.vue',
    ...LIST_SECTION_PARENTS
]

describe('vulnerability details panel wiring', () => {
    it.each(PANEL_HOSTS)('%s hosts the panel on the composable state', (host) => {
        const source = code(host)
        expect(source).toMatch(/import VulnerabilityDetailsModal from '[^']*VulnerabilityDetailsModal\.vue'/)
        expect(source).toMatch(/import \{ useVulnerabilityDetail \} from '[^']*useVulnerabilityDetail'/)
        expect(source).toMatch(/const \{ vulnDetail, openVulnDetail \} = useVulnerabilityDetail\(/)
        const panel = source.match(/<vulnerability-details-modal\b[^>]*>/)?.[0] || ''
        expect(panel).toContain('v-model:show="vulnDetail.show"')
        expect(panel).toContain(':vuln-id="vulnDetail.vulnId"')
        expect(panel).toMatch(/:org-uuid="[^"]+"/)
    })

    it('forwards vuln-click from every FindingListSection', () => {
        expect(LIST_SECTION_PARENTS.length).toBeGreaterThanOrEqual(3)
        for (const parent of LIST_SECTION_PARENTS) {
            // Quote-aware: attribute values contain '>' (v-if="... length > 0").
            const sections = code(parent).match(/<FindingListSection\b(?:[^>"']|"[^"]*"|'[^']*')*>/g) || []
            expect(sections.length, parent).toBeGreaterThan(0)
            for (const tag of sections) {
                expect(tag, parent).toContain('@vuln-click="openVulnDetail"')
            }
        }
    })

    it.each([
        ['utils/metrics.ts', ['findingTypeOf', 'renderFindingId']],
        ['components/VulnerabilityAnalysis.vue', ['findingTypeOf', 'renderFindingId']],
        ['components/ReleasesByCve.vue', ['findingTypeOfSearchedId', 'renderFindingId']],
        ['components/changelog/FindingListSection.vue', ['findingIdLink', 'findingTypeOf', 'followFindingIdLink']],
        ['components/KevDetailsModal.vue', ['cweUrlFor', 'osvUrlFor', 'openExternalLink']],
        ['components/VulnerabilityDetailsModal.vue', ['cweUrlFor', 'osvUrlFor', 'openExternalLink']]
    ])('%s imports the link helpers it calls', (file, names) => {
        const source = code(file)
        const importBlock = imports(source)
        for (const name of names) {
            expect(source, `${file} no longer uses ${name}`).toMatch(new RegExp(`\\b${name}\\(`))
            expect(importBlock, `${file} uses ${name} without importing it`).toMatch(new RegExp(`\\b${name}\\b`))
        }
    })

    it('keeps finding-link urls and the external-link consent in findingUtils only', () => {
        const offenders: string[] = []
        for (const rel of sources()) {
            if (rel === join('utils', 'findingUtils.ts')) continue
            const source = readFileSync(join(srcDir, rel), 'utf8')
            for (const marker of ['osv.dev/vulnerability', 'cwe.mitre.org', 'rearm_external_link_consent_until',
                'getFindingUrl', 'createVulnerabilityLink']) {
                if (source.includes(marker)) offenders.push(`${rel}: ${marker}`)
            }
        }
        expect(offenders).toEqual([])
    })
})
