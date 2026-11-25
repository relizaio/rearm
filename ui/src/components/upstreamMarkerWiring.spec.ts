// T-583: the ingested marker in the two large views, checked in their sources (they are too large
// to mount here). Both mount it behind the edition gate; the release view takes the artifacts of
// the ingested snapshot from it and keeps them read-only in its artifact table; neither view gains
// an HTML position for it.
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'

const src = (f: string) => readFileSync(fileURLToPath(new URL('./' + f, import.meta.url)), 'utf8')
const componentView = src('ComponentView.vue')
const releaseView = src('ReleaseView.vue')
const count = (s: string, what: string) => s.split(what).length - 1

/** The one element that mounts the marker, from its opening tag to its end. */
function markerElement (source: string): string {
    const start = source.indexOf('<upstream-ingested-marker ')
    expect(start).toBeGreaterThan(0)
    expect(source.indexOf('<upstream-ingested-marker ', start + 1)).toBe(-1)
    return source.slice(start, source.indexOf('/>', start) + 2)
}

/** The name of the computed the marker's v-if starts with, and that computed's definition line. */
function gateOf (source: string): string {
    const el = markerElement(source)
    const gate = el.match(/v-if="(\w+) && /)?.[1]
    expect(gate).toBeTruthy()
    const def = source.split('\n').find(l => l.startsWith('const ' + gate + ':') || l.startsWith('const ' + gate + ' '))
    expect(def).toBeTruthy()
    return def!
}

describe('T-583: the ingested marker in the component and release views', () => {
    it('ComponentView mounts the ITEM marker under a computed over upstreamSourcesAvailable, after the TEA banner', () => {
        const el = markerElement(componentView)
        expect(el).toContain('subject="ITEM"')
        expect(el).toContain(':uuid="componentUuid"')
        expect(gateOf(componentView)).toMatch(/= computed\(.*upstreamSourcesAvailable\(store\.getters\.myuser\?\.installationType\)\)/)
        expect(componentView.indexOf('<upstream-ingested-marker ')).toBeGreaterThan(componentView.indexOf('data-testid="tea-public-banner"'))
        expect(componentView).toContain("import UpstreamIngestedMarker from './upstream/UpstreamIngestedMarker.vue'")
    })

    it('ReleaseView mounts the RELEASE marker under a computed over upstreamSourcesAvailable and handles snapshot-artifacts', () => {
        const el = markerElement(releaseView)
        expect(el).toContain('subject="RELEASE"')
        expect(el).toContain(':uuid="releaseUuid"')
        expect(el).toContain('@snapshot-artifacts="onSnapshotArtifacts"')
        expect(gateOf(releaseView)).toMatch(/= computed\(.*upstreamSourcesAvailable\(store\.getters\.myuser\?\.installationType\)\)/)
        expect(releaseView).toMatch(/function onSnapshotArtifacts \(artifacts: string\[\]\) \{\s*snapshotArtifacts\.value = new Set\(artifacts\)\s*\}/)
        expect(releaseView.indexOf('<upstream-ingested-marker ')).toBeGreaterThan(releaseView.indexOf('data-testid="tea-public-banner"'))
    })

    it('ReleaseView decides with isSnapshotArtifact inside the action column of artifactsTableFields and leaves out the three changing actions', () => {
        const start = releaseView.indexOf('const artifactsTableFields: DataTableColumns<any> = [')
        expect(start).toBeGreaterThan(0)
        const table = releaseView.slice(start, releaseView.indexOf('\n]\n', start))
        const actions = table.slice(table.indexOf("key: 'actions'"))
        expect(actions).toContain('const ingested = isSnapshotArtifact(row.uuid, snapshotArtifacts.value)')
        expect(actions).toContain("'Part of the ingested snapshot: read-only'")
        for (const title of ['Upload New Artifact Version', 'Edit Artifact Tags', 'Delete Artifact from Release']) {
            const line = actions.split('\n').find(l => l.includes("title: '" + title + "'"))
            expect(line, title).toBeTruthy()
            const guard = title === 'Delete Artifact from Release'
                ? actions.split('\n').find(l => l.includes("release.value.lifecycle === 'DRAFT' && row.belongsTo === 'Release'"))
                : line
            expect(guard, title).toMatch(/if \(!ingested/)
        }
        expect(actions).toContain('renderArtifactActionIcons(row)')
    })

    it('adds no HTML position: the release view keeps its one, the component view its none', () => {
        expect(count(releaseView, 'v-html')).toBe(1)
        expect(count(componentView, 'v-html')).toBe(0)
    })
})
