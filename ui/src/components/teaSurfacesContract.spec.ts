// The three TEA profile surfaces (task TEA-2, design 4.8), checked in the hosts' sources: the views
// are too large to mount here, and these are the lines the design names.
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const src = (f: string) => readFileSync(resolve(__dirname, f), 'utf8')

describe('TEA surfaces contract', () => {
    it('ComponentView carries the TEA tab after the Guards tab, in both editions, and the page banner under the title', () => {
        const cv = src('ComponentView.vue')
        const guards = cv.indexOf('tab="Guards"')
        const tea = cv.indexOf('<n-tab-pane name="tea" tab="TEA">')
        expect(guards).toBeGreaterThan(0)
        expect(tea).toBeGreaterThan(guards)
        expect(cv.slice(tea, cv.indexOf('</n-tab-pane>', tea))).toContain('<TeaProfileEditor')
        const title = cv.indexOf('<h5 v-if="componentData">')
        const banner = cv.indexOf('data-testid="tea-public-banner"')
        expect(banner).toBeGreaterThan(title)
        expect(banner).toBeLessThan(cv.indexOf('<div class="componentIconsAndSettings">'))
        expect(cv).toMatch(/<n-alert v-if="teaBanner" data-testid="tea-public-banner" type="error" :closable="false"/)
    })

    it('OrgSettings carries the Transparency Exchange tab for org admins and the TEA action of every perspective', () => {
        const os = src('OrgSettings.vue')
        expect(os).toContain('<n-tab-pane name="tea" tab="Transparency Exchange" v-if="isOrgAdmin">')
        const pane = os.indexOf('tab="Transparency Exchange"')
        expect(os.slice(pane, os.indexOf('</n-tab-pane>', pane))).toContain('<TeaOrgPanel')
        expect(os).toContain("title: 'TEA profile'")
        expect(os).toContain("'data-testid': 'perspective-tea-action'")
        expect(os).toContain(":scope=\"selectedPerspectiveType === 'PRODUCT' ? 'COMPONENT' : 'PERSPECTIVE'\"")
        expect(os).toMatch(/:title="'TEA profile of perspective: ' \+ selectedPerspectiveName"/)
    })

    it('OrgSettings loads the perspectives (Pro) and products for the Transparency Exchange tab itself', () => {
        const os = src('OrgSettings.vue')
        const start = os.indexOf('async function loadTabSpecificData')
        const body = os.slice(start, os.indexOf('\n}\n', start))
        const branch = body.indexOf('} else if (tabName === "tea") {')
        expect(branch).toBeGreaterThan(0)
        const tea = body.slice(branch, body.indexOf('} else if', branch + 1))
        expect(tea).toContain("...(myUser.value.installationType !== 'OSS' ? [loadPerspectives()] : [])")
        expect(tea).toContain("store.dispatch('fetchProducts', orgResolved.value)")
    })

    it('graphqlQueries holds the five TEA documents', () => {
        const q = src('../utils/graphqlQueries.ts')
        for (const doc of ['TEA_PROFILE_EDITOR_VIEW', 'TEA_PROFILES_OF_ORG', 'TEA_ORG_DISCOVERY', 'SAVE_TEA_PROFILE', 'DELETE_TEA_PROFILE']) {
            expect(q).toContain('const ' + doc + ' = gql`')
        }
        expect(q).toContain('teaProfileEditorView(org: $org, scope: $scope, object: $object)')
        expect(q).toContain('saveTeaProfile(org: $org, scope: $scope, object: $object, profile: $profile)')
    })
})
