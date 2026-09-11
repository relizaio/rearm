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
    it('hides itself until the first answer, and for good when the backend lacks the query', () => {
        expect(source).toMatch(/v-if="fleetRiskLoaded && fleetRisk\.supported"/)
        // Only an unsupported backend stops the re-asking; a failed load must not.
        expect(source).toMatch(/if \(fleetRisk\.value\.supported\) loadFleetRisk\(1\)/)
        expect(source).not.toMatch(/fleetRisk\.value = FLEET_RISK_UNSUPPORTED/)
    })

    it('reads through the shared loader, not an inline document', () => {
        expect(source).toMatch(/import \{[^}]*loadDevicesAtSupportRisk[^}]*\} from '@\/utils\/fleetSupportRisk'/)
        expect(source).toMatch(/await loadDevicesAtSupportRisk\(graphqlClient, \{/)
        expect(source).not.toMatch(/gql`[^`]*devicesAtSupportRisk/)
    })

    /**
     * A panel that swallowed every error would read "no risk" on a 403. The loader already
     * turns schema drift into `supported: false`; anything else must reach the operator --
     * and must NOT hide the panel, or a transient failure erases the report.
     */
    it('shows a real load failure over the last rows, with a retry, instead of hiding', () => {
        const fn = source.slice(source.indexOf('async function loadFleetRisk'), source.indexOf('const blankCell'))
        expect(fn).toMatch(/fleetRiskError\.value = e\?\.message/)
        expect(fn).toMatch(/notify\('error', 'Failed'/)
        expect(source).toMatch(/<n-alert v-if="fleetRiskError" type="error"/)
        expect(source).toMatch(/@click="loadFleetRisk\(fleetRiskPage\)">Retry</)
    })

    /**
     * Two loads in flight (client switched while the org-wide page was slow) must resolve to
     * the NEWER scope, whichever answers last. Pinned on the ticket check after the await.
     */
    it('lets only the newest request write its result', () => {
        const fn = source.slice(source.indexOf('async function loadFleetRisk'), source.indexOf('const blankCell'))
        expect(fn).toMatch(/const ticket = \+\+fleetRiskTicket/)
        expect(fn).toMatch(/if \(ticket !== fleetRiskTicket\) return\s+fleetRisk\.value = result/)
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

    it('shows the degraded alert only on a CORE-served page and stops asking for FULL after a rejection', () => {
        expect(source).toMatch(/<n-alert v-if="fleetRisk\.degraded"/)
        expect(source).toMatch(/skipFull: fleetRisk\.value\.degraded/)
    })

    /**
     * The row window must not carry the provenance clause effectiveWindowLabel adds: this
     * query does not select `source` (the backend serialises it as null here), so the
     * shipment-form helper would attribute every row's window to the product component.
     */
    it('labels the row window without a provenance clause', () => {
        expect(source).toMatch(/'window' in r \? fleetWindowLabel\(r\.window\)/)
        const fleet = source.slice(source.indexOf('// ---- fleet support risk'))
        expect(fleet).not.toMatch(/buildEffectiveWindowLabel/)
        // and the flagged tooltip is the roster wording, not the per-component one
        expect(fleet).toMatch(/FLEET_RISK_DETAIL\[r\.risk\]/)
        expect(fleet).not.toMatch(/DEVICE_RISK_DETAIL/)
        expect(fleet).not.toMatch(/=== 'REPORTED'/)
    })

    it('reads enrichment fields behind a presence guard', () => {
        for (const f of ['releaseSource', 'window', 'earliestComponentEos', 'componentsDrivingRisk']) {
            expect(source, `${f} must be presence-guarded`).toMatch(new RegExp(`'${f}' in r`))
        }
    })

    it('imports the naive-ui components it renders', () => {
        const imp = source.match(/import \{([^}]*)\} from 'naive-ui'/)?.[1] || ''
        for (const c of ['NAlert', 'NDataTable', 'NTag', 'NTooltip', 'NSpace', 'NButton']) expect(imp).toContain(c)
    })

    it('loads on mount and links each row to the device twin', () => {
        const mounted = source.slice(source.indexOf('onMounted(async () => {'))
        expect(mounted).toMatch(/loadFleetRisk\(1\)/)
        const rowProps = source.match(/const fleetRiskRowProps = [^\n]*/)?.[0] || ''
        expect(rowProps).toMatch(/name: 'DeviceView'/)
        expect(rowProps).toMatch(/deviceuuid: r\.device\b/)
        expect(source).toMatch(/:row-props="fleetRiskRowProps"/)
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
