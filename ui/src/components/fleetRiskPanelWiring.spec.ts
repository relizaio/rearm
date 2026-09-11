import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'

/**
 * The fleet support-risk panel is actually WIRED into the distribution page.
 *
 * Same reasoning as deviceWindowPanelWiring.spec.ts: no vue-tsc, unlinted utils, so a green
 * build proves nothing about a template binding or a `<script setup>` identifier. These pin
 * the things whose absence makes the panel invisible while every unit test passes.
 */
const source = readFileSync(
    fileURLToPath(new URL('./DistributionOfOrg.vue', import.meta.url)), 'utf8')

describe('the fleet support-risk panel', () => {
    it('hides itself when the backend does not support the query', () => {
        expect(source).toMatch(/<div v-if="fleetRisk\.supported" class="fleetRiskPanel"/)
    })

    it('reads through the shared loader, not an inline document', () => {
        expect(source).toMatch(/import \{[^}]*loadDevicesAtSupportRisk[^}]*\} from '@\/utils\/fleetSupportRisk'/)
        expect(source).toMatch(/await loadDevicesAtSupportRisk\(graphqlClient, \{/)
        expect(source).not.toMatch(/gql`[^`]*devicesAtSupportRisk/)
    })

    /**
     * A panel that swallowed every error would read "no risk" on a 403. The loader already
     * turns schema drift into `supported: false`; anything else must reach the operator.
     */
    it('notifies on a real load failure instead of swallowing it', () => {
        const fn = source.slice(source.indexOf('async function loadFleetRisk'), source.indexOf('const fleetRiskColumns'))
        expect(fn).toMatch(/notify\('error', 'Fleet support risk'/)
    })

    it('is paged remotely with the server total, and the page change reloads', () => {
        expect(source).toMatch(/<n-data-table remote :columns="fleetRiskColumns"/)
        expect(source).toMatch(/:pagination="fleetRiskPagination" @update:page="loadFleetRisk"/)
        expect(source).toMatch(/itemCount: fleetRisk\.value\.total/)
        // one-based in the table, zero-based on the wire
        expect(source).toMatch(/page: Math\.max\(0, page - 1\)/)
    })

    it('follows the client / site selection as its filter', () => {
        expect(source).toMatch(/clientUuid: selectedClientUuid\.value \|\| null/)
        expect(source).toMatch(/siteUuid: selectedSiteUuid\.value \|\| null/)
        expect(source).toMatch(/watch\(\[selectedClientUuid, selectedSiteUuid\]/)
    })

    it('shows the degraded alert only on a CORE-served page and remembers the rejection', () => {
        expect(source).toMatch(/<n-alert v-if="fleetRisk\.degraded"/)
        expect(source).toMatch(/if \(result\.supported && result\.degraded\) fleetRiskFullRejected\.value = true/)
        expect(source).toMatch(/skipFull: fleetRiskFullRejected\.value/)
    })

    it('reads enrichment fields behind a presence guard', () => {
        for (const f of ['releaseSource', 'window', 'earliestComponentEos', 'componentsDrivingRisk']) {
            expect(source, `${f} must be presence-guarded`).toMatch(new RegExp(`'${f}' in r`))
        }
    })

    it('imports the naive-ui components it renders', () => {
        const imp = source.match(/import \{([^}]*)\} from 'naive-ui'/)?.[1] || ''
        for (const c of ['NAlert', 'NDataTable', 'NTag', 'NTooltip', 'NSpace']) expect(imp).toContain(c)
    })

    it('loads on mount and links each row to the device twin', () => {
        const mounted = source.slice(source.indexOf('onMounted(async () => {'))
        expect(mounted).toMatch(/loadFleetRisk\(1\)/)
        expect(source).toMatch(/const fleetRiskRowProps = \(r: FleetRiskRow\) => \(\{ style: 'cursor: pointer;', onClick: \(\) => router\.push\(\{ name: 'DeviceView', params: \{ deviceuuid: r\.device \} \}\) \}\)/)
    })

    it('sits above the three-column grid as a sibling of the drift panel, not inside it', () => {
        const drift = source.indexOf('class="fleetDriftPanel"')
        const driftClose = source.indexOf('</div>', drift)
        const risk = source.indexOf('class="fleetRiskPanel"')
        const grid = source.indexOf('<div class="distWrapper">')
        expect(drift).toBeGreaterThan(-1)
        expect(risk).toBeGreaterThan(driftClose)
        expect(grid).toBeGreaterThan(risk)
    })
})
