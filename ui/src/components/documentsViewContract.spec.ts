// The DOCUMENT component view and the lists (task 36d0549e), checked in the views' sources: the
// components are too large to mount here, and these are the lines the design names.
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const src = (f: string) => readFileSync(resolve(__dirname, f), 'utf8')

describe('documents view contract', () => {
    it('ComponentView shows the document panel and hides the software ones for a DOCUMENT component', () => {
        const cv = src('ComponentView.vue')
        expect(cv).toContain('<n-gi v-if="isDocument" span="10">')
        expect(cv).toContain('<document-component-panel')
        expect(cv).toContain('<n-gi v-else-if="myview === \'devops\'" span="10">')
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
})
