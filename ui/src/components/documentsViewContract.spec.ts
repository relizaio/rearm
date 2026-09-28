// The DOCUMENT component view and the lists (task 36d0549e), checked in the views' sources: the
// components are too large to mount here, and these are the lines the design names.
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const src = (f: string) => readFileSync(resolve(__dirname, f), 'utf8')

describe('documents view contract', () => {
    it('ComponentView shows the document panel and hides the software ones for a DOCUMENT component', () => {
        const cv = src('ComponentView.vue')
        // The panel sits below the component's title (RD2-24 run 1, T-2), and a document shows no charts.
        const panel = cv.indexOf('<document-component-panel v-if="isDocument"')
        expect(panel).toBeGreaterThan(cv.indexOf('<h5 v-if="componentData">'))
        expect(panel).toBeLessThan(cv.indexOf('<div class="componentDetails" v-if="!isDocument">'))
        expect(cv).toContain('<n-gi v-if="!isDocument && myview === \'devops\'" span="10">')
        expect(cv).toContain('<n-gi v-else-if="!isDocument" span="10">')
        expect(cv).toContain('<div class="componentDetails" v-if="!isDocument">')
        expect(cv).toContain('v-if="!isDocument && selectedTab !== \'latest\'"')
        expect(cv).toMatch(/genApiKey\('rlz'\)/)
        expect(cv).toMatch(/isWritable && !isDocument" @click="genApiKey/)
        expect(cv).toMatch(/v-if="componentData && !isDocument" @click="navigateToVulnAnalysis"/)
        expect(cv).toMatch(/tab="Actions" v-if="[^"]*!isDocument"/)
        expect(cv).toMatch(/tab="Rules" v-if="[^"]*!isDocument"/)
        expect(cv).toMatch(/v-if="updatedComponent && componentData && !isDocument">\s*<label id="componentVersionSchemaLabel" for="componentVersionSchema">Version Schema/)
        expect(cv).toContain('value="Document" readonly')
    })

    it('the components cache asks for the software kinds, and so do the perspective and classification lists', () => {
        expect(src('../store.ts')).toContain('kinds: SOFTWARE_KINDS')
        expect(src('../store.ts')).toContain('kinds: $kinds')
        expect(src('../utils/graphqlQueries.ts')).toContain('componentsOfPerspective(perspectiveUuid: $perspectiveUuid, kinds: [GENERIC, HELM])')
        expect(src('OrgSettings.vue')).toContain('kinds: [GENERIC, HELM]')
        expect(src('DistributionOfOrg.vue')).toContain('kinds: [GENERIC, HELM]')
    })

    it('BranchView lists software dependencies and groups the documents, collapsed', () => {
        const bv = src('BranchView.vue')
        expect(bv).toContain(':data="dependencyGroups.software"')
        expect(bv).toContain('v-if="dependencyGroups.documents.length"')
        expect(bv).toContain('<n-collapse-item :title="`Documents (${dependencyGroups.documents.length})`"')
    })

    it('the PERT and timeline cards lead with the key', () => {
        expect(src('AiAgentTaskPertView.vue')).toContain("return refWithLevel(cardRef(t, 'draft'), t, props.board)")
        expect(src('AiAgentTaskTimelineView.vue')).toContain('return refWithLevel(cardRef(t, (t.title ?? \'\').slice(0, 12)), t, props.board)')
    })

    it('ReleaseView shows a document round as the round, without the software panels (RD2-24)', () => {
        const rv = src('ReleaseView.vue')
        expect(rv).toContain('const isDocumentRound = computed(() => !!release.value?.document)')
        expect(rv).toContain('<div class="container" v-if="isDocumentRound && documentRound" data-testid="document-round">')
        for (const field of ['label="Document"', 'label="Round"', 'label="File"', 'data-testid="round-task"',
            'data-testid="round-board"', 'label="Findings"', 'data-testid="round-checks"', 'label="Elements"']) {
            expect(rv, field).toContain(field)
        }
        // the four software panels: vulnerabilities, the SBOM changes and BOM components, deliverables, VEX
        expect(rv).toContain('<n-gi v-if="!isDocumentRound" span="2">')
        expect(rv).toContain("v-if=\"updatedRelease.componentDetails.type === 'COMPONENT' && !isHardware && !isDocumentRound\"")
        expect(rv).toContain('<n-tab-pane v-if="!isDocumentRound" name="bomComponents" tab="BOM Components">')
        expect(rv).toContain("<div class=\"container\" v-if=\"updatedRelease.componentDetails.type === 'COMPONENT' && !isDocumentRound\">")
        expect(rv).toContain('<n-tab-pane v-if="!isDocumentRound" name="vex" tab="VEX">')
        expect(rv).toContain("roundTask.value = await store.dispatch('fetchDocumentRoundTask', task)")
    })

    it('the round page spaces its links and verdict, hides an empty File row, and the store keeps each doc with its read (RD2-24 run 1)', () => {
        const rv = src('ReleaseView.vue')
        const round = rv.slice(rv.indexOf('data-testid="document-round"'), rv.indexOf('</n-descriptions>', rv.indexOf('data-testid="document-round"')))
        expect(round).not.toContain('class="ml-2"')
        expect(rv.match(/class="round-gap"/g)?.length).toBe(2)
        expect(rv).toContain('.round-gap { margin-left: 8px; }')
        expect(rv).toContain('<n-descriptions-item v-if="documentRound.path" label="File">')
        const store = src('../store.ts')
        const task = store.indexOf('async fetchDocumentRoundTask')
        const rounds = store.indexOf('async fetchDocumentRounds (')
        expect(store.slice(store.lastIndexOf('/**', task), task)).toContain('The task a document round belongs to')
        expect(store.slice(store.lastIndexOf('/**', rounds), rounds)).toContain("A document component's rounds")
    })

    it('a round\'s Components tab shows the round block and its Source Code Entries, nothing else (RD2-24 architecture-2 §2)', () => {
        const rv = src('ReleaseView.vue')
        const start = rv.indexOf('<n-tab-pane name="components" tab="Components">')
        const pane = rv.slice(start, rv.indexOf('</n-tab-pane>', start))
        const sections = [...pane.matchAll(/<div class="container"(?: v-if="([^"]*)")?[^>]*>\s*<h3>\s*([^<\n]*?)\s*(?:<|\n)/g)]
            .map(m => ({ guard: m[1] ?? '', heading: m[2] }))
        expect(sections.map(s => s.heading)).toEqual(['Document round', 'Components', 'Source Code Entries',
            'Source Code Entries from Failed/Pending Releases', 'Artifacts', 'Produced Deliverables', 'Inbound Deliverables'])
        // A round's component is never a PRODUCT, so a PRODUCT-only section does not render for one.
        const shownForARound = sections.filter(s => s.guard !== 'false' && !s.guard.includes('!isDocumentRound')
            && !/componentDetails\.type === 'PRODUCT'$/.test(s.guard))
        expect(shownForARound.map(s => s.heading)).toEqual(['Document round', 'Source Code Entries'])
        expect(sections.find(s => s.heading === 'Source Code Entries')?.guard).not.toContain('isDocumentRound')
    })
})
