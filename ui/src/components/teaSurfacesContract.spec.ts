// The three TEA profile surfaces (task TEA-2, design 4.8), checked in the hosts' sources: the views
// are too large to mount here, and these are the lines the design names.
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const src = (f: string) => readFileSync(resolve(__dirname, f), 'utf8')

describe('TEA surfaces contract', () => {
    it('ComponentView carries the TEA tab after the Device tab and before Actions and Guards, and the page banner under the title', () => {
        const cv = src('ComponentView.vue')
        const device = cv.indexOf('<n-tab-pane name="device" tab="Device"')
        const tea = cv.indexOf('<n-tab-pane name="tea" tab="TEA" v-if="teaProfilesAvailable(myUser.installationType)">')
        expect(device).toBeGreaterThan(0)
        expect(tea).toBeGreaterThan(device)
        expect(tea).toBeLessThan(cv.indexOf('tab="Actions"'))
        expect(tea).toBeLessThan(cv.indexOf('tab="Guards"'))
        expect(cv.slice(tea, cv.indexOf('</n-tab-pane>', tea))).toContain('<TeaProfileEditor')
        const title = cv.indexOf('<h5 v-if="componentData">')
        const banner = cv.indexOf('data-testid="tea-public-banner"')
        expect(banner).toBeGreaterThan(title)
        expect(banner).toBeLessThan(cv.indexOf('<div class="componentIconsAndSettings">'))
        expect(cv).toMatch(/<n-alert v-if="teaBanner" data-testid="tea-public-banner" type="error" :closable="false"/)
    })

    it('OrgSettings carries the Transparency Exchange API sub-tab of Admin Settings and the TEA action of every perspective', () => {
        const os = src('OrgSettings.vue')
        const admin = os.indexOf('<n-tab-pane name="adminSettings" tab="Admin Settings" v-if="isOrgAdmin">')
        const line = '<n-tab-pane name="tea" tab="Transparency Exchange API" v-if="teaProfilesAvailable(myUser.installationType)">'
        const pane = os.indexOf(line)
        expect(admin).toBeGreaterThan(0)
        expect(pane).toBeGreaterThan(admin)
        expect(pane).toBeLessThan(os.indexOf('<n-tab-pane name="audit"'))
        expect(os.slice(pane, os.indexOf('</n-tab-pane>', pane))).toContain('<TeaOrgPanel')
        // No top-level TEA pane any more.
        expect(os).not.toContain('tab="Transparency Exchange"')
        expect(os).toContain("title: 'TEA profile'")
        expect(os).toContain("'data-testid': 'perspective-tea-action'")
        expect(os).toContain(":scope=\"selectedPerspectiveType === 'PRODUCT' ? 'COMPONENT' : 'PERSPECTIVE'\"")
        expect(os).toMatch(/:title="'TEA profile of perspective: ' \+ selectedPerspectiveName"/)
    })

    it('OrgSettings loads the perspectives (Pro) and products for the SBOM Profiles, Regulatory and TEA sub-tabs itself', () => {
        const os = src('OrgSettings.vue')
        const start = os.indexOf('async function loadAdminSubTabData')
        expect(start).toBeGreaterThan(0)
        const body = os.slice(start, os.indexOf('\n}\n', start))
        expect(body).toContain('if (subTab === "sbomProfiles" || subTab === "regulatory" || subTab === "tea") {')
        expect(body).toContain("...(myUser.value.installationType !== 'OSS' ? [loadPerspectives()] : [])")
        expect(body).toContain("store.dispatch('fetchProducts', orgResolved.value)")
        // Run when Admin Settings loads (its current sub-tab), on a sub-tab switch and on a ?adminTab= push.
        const tabs = os.slice(os.indexOf('async function loadTabSpecificData'))
        const admin = tabs.slice(tabs.indexOf('} else if (tabName === "adminSettings") {'))
        expect(admin.slice(0, admin.indexOf('\n}\n'))).toContain('loadAdminSubTabData(adminSubTab.value)')
        const sw = os.slice(os.indexOf('async function handleAdminSubTabSwitch'))
        expect(sw.slice(0, sw.indexOf('\n}\n'))).toContain('query: { ...route.query, adminTab: tabName }')
        expect(sw.slice(0, sw.indexOf('\n}\n'))).toContain('loadAdminSubTabData(tabName)')
        const watcher = os.slice(os.indexOf('watch(() => [route.query.tab, route.query.auditTab, route.query.policyTab, route.query.adminTab]'))
        expect(watcher.slice(0, watcher.indexOf('\n})\n'))).toContain('loadAdminSubTabData(adt)')
        // The top-level TEA and Profiles branches are gone with their panes.
        expect(os).not.toContain('tabName === "tea"')
        expect(os).not.toContain('tabName === "profiles"')
    })

    it('OrgSettings nests General, Terminology, SBOM Profiles, Regulatory and Transparency Exchange API under Admin Settings, in order', () => {
        const os = src('OrgSettings.vue')
        const admin = os.indexOf('<n-tab-pane name="adminSettings" tab="Admin Settings" v-if="isOrgAdmin">')
        const audit = os.indexOf('<n-tab-pane name="audit"')
        const group = os.slice(admin, audit)
        expect(group).toContain('<n-tabs type="segment" :value="adminSubTab" @update:value="handleAdminSubTabSwitch"')
        const lines = [
            '<n-tab-pane name="general" tab="General">',
            '<n-tab-pane name="terminology" tab="Terminology">',
            '<n-tab-pane name="sbomProfiles" tab="SBOM Profiles" v-if="teaProfilesAvailable(myUser.installationType)">',
            '<n-tab-pane name="regulatory" tab="Regulatory" v-if="regulatoryPackagesAvailable(myUser.installationType)">',
            '<n-tab-pane name="tea" tab="Transparency Exchange API" v-if="teaProfilesAvailable(myUser.installationType)">',
        ]
        const at = lines.map(l => group.indexOf(l))
        for (const [i, l] of lines.entries()) expect(at[i], l).toBeGreaterThan(0)
        expect([...at].sort((a, b) => a - b)).toEqual(at)
        const pane = (l: string) => group.slice(group.indexOf(l), group.indexOf('</n-tab-pane>', group.indexOf(l)))
        expect(pane(lines[0])).toContain('<h5>Violation Ignore Regexes</h5>')
        expect(pane(lines[1])).toContain('<h5>Custom Terminology</h5>')
        expect(pane(lines[2])).toContain('<SbomProfilesPanel')
        const regulatory = pane(lines[3])
        expect(regulatory).toContain('<PackageSettingsEditor')
        expect(regulatory).toContain('scope="ORGANIZATION"')
        expect(regulatory).toContain(':is-writable="isOrgAdmin"')
        expect(regulatory).toContain(':names="perspectiveNamesMap"')
        // The top-level Terminology and Profiles panes are gone.
        expect(os.slice(0, admin)).not.toContain('name="terminology"')
        expect(os).not.toContain('name="profiles"')
    })

    it('OrgSettings falls back to General for an unknown or hidden admin sub-tab', () => {
        const os = src('OrgSettings.vue')
        expect(os).toContain("const DEFAULT_ADMIN_SUBTAB = 'general'")
        expect(os).toContain("const ADMIN_SUBTABS = ['general', 'terminology', 'sbomProfiles', 'regulatory', 'tea']")
        const fn = os.slice(os.indexOf('function isAdminSubTabAccessible'), os.indexOf('\n}\n', os.indexOf('function isAdminSubTabAccessible')))
        expect(fn).toContain('if (!ADMIN_SUBTABS.includes(t)) return false')
        expect(fn).toContain("if (t === 'regulatory') return regulatoryPackagesAvailable(myUser.value?.installationType)")
        expect(fn).toContain("if (t === 'sbomProfiles' || t === 'tea') return teaProfilesAvailable(myUser.value?.installationType)")
        expect(os).toContain('return t && isAdminSubTabAccessible(t) ? t : DEFAULT_ADMIN_SUBTAB')
        expect(os).toContain('const adminSubTab = ref(resolveAdminSubTab(route.query.adminTab as string))')
    })

    it('neither host reads a TEA profile where the backend serves none (no CE backend sync, 2026-10-03)', () => {
        const cv = src('ComponentView.vue')
        const start = cv.indexOf('async function loadTeaBanner')
        const body = cv.slice(start, cv.indexOf('\n}\n', start))
        expect(body.indexOf('if (!teaProfilesAvailable(myUser.installationType)) {')).toBeGreaterThan(0)
        expect(body.indexOf('if (!teaProfilesAvailable(myUser.installationType)) {'))
            .toBeLessThan(body.indexOf('graphqlQueries.TeaProfileEditorViewGql'))
        // TEA is a sub-tab of the admin-only Admin Settings now, and no longer a top-level tab of its own.
        const os = src('OrgSettings.vue')
        expect(os).toMatch(/const adminOnlyTabs = \[[^\]]*'adminSettings'[^\]]*\]/)
        expect(os).not.toMatch(/const proOnlyTabs = \[[^\]]*'tea'[^\]]*\]/)
        expect(os).not.toMatch(/const adminOnlyTabs = \[[^\]]*'tea'[^\]]*\]/)
    })

    it('graphqlQueries holds the nine TEA documents', () => {
        const q = src('../utils/graphqlQueries.ts')
        const docs = ['TEA_PROFILE_EDITOR_VIEW', 'TEA_PROFILES_OF_ORG', 'TEA_ORG_DISCOVERY', 'SAVE_TEA_PROFILE', 'DELETE_TEA_PROFILE',
            'TEA_PROFILE_EXPOSURE_CHANGE', 'TEA_COMPONENT_NAME', 'TEA_MEMBERSHIP_EXPOSURE_CHANGE', 'TEA_COMPONENT_PERSPECTIVES']
        expect(docs).toHaveLength(9)
        for (const doc of docs) {
            expect(q).toContain('const ' + doc + ' = gql`')
        }
        expect(q).toContain('teaProfileEditorView(org: $org, scope: $scope, object: $object)')
        expect(q).toContain('saveTeaProfile(org: $org, scope: $scope, object: $object, profile: $profile)')
        expect(q).toContain('teaProfileExposureChange(org: $org, scope: $scope, object: $object, profile: $profile)')
        expect(q).toContain('teaMembershipExposureChange(componentUuid: $componentUuid, perspectiveUuids: $perspectiveUuids)')
        expect(q).toContain('TeaMembershipExposureChangeGql: TEA_MEMBERSHIP_EXPOSURE_CHANGE,')
        expect(q).toContain('TeaComponentPerspectivesGql: TEA_COMPONENT_PERSPECTIVES,')
    })

    // TEA-9 round 2 (design 3.11): the UI write of setPerspectivesOnComponent confirms on the
    // membership dry-run of exactly the list it sends, before it sends it. The OrgSettings members
    // dialog adds through main's addPerspectiveToComponent since the main merge (TEA-16): see below.
    const MEMBERSHIP_WRITES: [string, string][] = [
        ['ComponentView.vue', 'async function savePerspectives'],
    ]
    it.each(MEMBERSHIP_WRITES)('%s %s queries the membership dry-run and confirms before setPerspectivesOnComponent', (file, fn) => {
        const f = src(file)
        const start = f.indexOf(fn + '(')
        expect(start).toBeGreaterThan(0)
        const body = f.slice(start, f.indexOf('\n}\n', start))
        const gate = body.indexOf('if (teaProfilesAvailable(')
        const dryRun = body.indexOf('graphqlQueries.TeaMembershipExposureChangeGql')
        const confirm = body.indexOf('teaConfirmBeforeMembership(')
        const swal = body.indexOf('Swal.fire({ ...confirm, icon: \'warning\', showCancelButton: true')
        const mutation = body.indexOf('setPerspectivesOnComponent(')
        expect(gate).toBeGreaterThan(0)
        expect(dryRun).toBeGreaterThan(gate)
        expect(body.slice(dryRun, confirm)).toContain("fetchPolicy: 'no-cache'")
        expect(confirm).toBeGreaterThan(dryRun)
        expect(swal).toBeGreaterThan(confirm)
        expect(body.slice(swal, mutation)).toContain('if (!answer?.isConfirmed) return')
        expect(mutation).toBeGreaterThan(swal)
        const delta = body.indexOf('teaMembershipDelta(')
        expect(delta).toBeGreaterThan(dryRun)
        expect(delta).toBeLessThan(confirm)
    })

    // TEA-16: main's one-membership add, with TEA-9's dry-run on the list the add produces (the stored
    // perspectives, read fresh, plus the selected one) in one helper in front of the add.
    it('OrgSettings confirms on the membership dry-run of the list the add produces, read fresh, before addPerspectiveToComponent', () => {
        const os = src('OrgSettings.vue')
        const hStart = os.indexOf('async function confirmTeaMembershipAdd (')
        expect(hStart).toBeGreaterThan(0)
        const helper = os.slice(hStart, os.indexOf('\n}\n', hStart))
        const gate = helper.indexOf('if (!teaProfilesAvailable(')
        const stored = helper.indexOf('graphqlQueries.TeaComponentPerspectivesGql')
        const dryRun = helper.indexOf('graphqlQueries.TeaMembershipExposureChangeGql')
        const confirm = helper.indexOf('teaConfirmBeforeMembership(')
        const delta = helper.indexOf('teaMembershipDelta(')
        const swal = helper.indexOf("Swal.fire({ ...confirm, icon: 'warning', showCancelButton: true")
        const answer = helper.indexOf('return !!answer?.isConfirmed')
        expect(gate).toBeGreaterThan(0)
        expect(stored).toBeGreaterThan(gate)
        expect(helper.slice(stored, dryRun)).toContain("fetchPolicy: 'no-cache'")
        expect(helper.slice(stored, dryRun)).toContain('const current: any[] = storedResp.data.component?.perspectiveDetails ?? []')
        expect(dryRun).toBeGreaterThan(stored)
        expect(helper.slice(dryRun, confirm)).toContain("fetchPolicy: 'no-cache'")
        expect(delta).toBeGreaterThan(dryRun)
        expect(delta).toBeLessThan(confirm)
        expect(swal).toBeGreaterThan(confirm)
        expect(answer).toBeGreaterThan(swal)
        for (const [fn, sel, word] of [['addComponentToPerspective', 'selectedComponentToAdd', 'component'],
            ['addProductToPerspective', 'selectedProductToAdd', 'product']]) {
            const start = os.indexOf('async function ' + fn + '(')
            expect(start).toBeGreaterThan(0)
            const body = os.slice(start, os.indexOf('\n}\n', start))
            const ask = body.indexOf('if (!(await confirmTeaMembershipAdd(' + sel + ".value, '" + word + "'))) return")
            expect(ask, fn).toBeGreaterThan(0)
            expect(ask, fn).toBeLessThan(body.indexOf('await addPerspectiveToComponent(graphqlClient,'))
        }
    })

    it('the membership dry-run sends exactly the list each write sends', () => {
        const cv = src('ComponentView.vue')
        const save = cv.slice(cv.indexOf('async function savePerspectives('), cv.indexOf('\nfunction resetPerspectives'))
        expect(save).toContain('variables: { componentUuid: componentUuid, perspectiveUuids: selectedPerspectives.value }')
        expect(save).toContain('perspectiveUuids: selectedPerspectives.value\n')
        expect(save).toContain("isComponent.value ? 'component' : 'product'")
        // OrgSettings (TEA-16): the helper reads the component being added and asks about the list
        // the add produces, the stored perspectives plus the selected one.
        const os = src('OrgSettings.vue')
        const hStart = os.indexOf('async function confirmTeaMembershipAdd (')
        const helper = os.slice(hStart, os.indexOf('\n}\n', hStart))
        expect(helper).toContain('variables: { componentUuid }')
        expect(helper).toContain('variables: { componentUuid, perspectiveUuids: afterAdd }')
        const afterAddLine = helper.split('\n').find(l => l.includes('const afterAdd: string[] = [...current.map('))
        expect(afterAddLine).toBeDefined()
        expect(afterAddLine).toContain('selectedPerspectiveUuid.value]')
        expect(helper).toContain('teaMembershipDelta(afterAdd, current, perspectives.value)')
    })

    it('ComponentView tells the TEA editor whether the component is archived (TEA-9)', () => {
        const cv = src('ComponentView.vue')
        const tea = cv.indexOf('<n-tab-pane name="tea" tab="TEA"')
        const editor = cv.slice(tea, cv.indexOf('</n-tab-pane>', tea))
        expect(editor).toContain(`:object-archived="componentData?.status === 'ARCHIVED'"`)
        // The page loads the component through ComponentFullData; without status the notice never shows.
        const q = src('../utils/graphqlQueries.ts')
        const start = q.indexOf('const COMPONENT_FULL_DATA = `')
        const full = q.slice(start, q.indexOf('`', start + 'const COMPONENT_FULL_DATA = `'.length))
        expect(full.split('\n').map(l => l.trim())).toContain('status')
    })

    it('graphqlQueries holds the four TEA publication documents (TEA-5)', () => {
        const q = src('../utils/graphqlQueries.ts')
        const docs = ['RELEASE_TEA_PUBLICATION_VIEW', 'PUBLISH_RELEASE_ON_TEA', 'REPUBLISH_RELEASE_ON_TEA', 'HIDE_RELEASE_ON_TEA']
        for (const doc of docs) {
            expect(q).toContain('const ' + doc + ' = gql`')
        }
        expect(q.match(/^const \w+ = gql`\n(query ReleaseTeaPublicationView|mutation (Publish|Republish|Hide)ReleaseOnTea)\(/gm)).toHaveLength(docs.length)
    })

    // EXTERNAL API keys (task TEA-3, design 3.11 and 4.10)
    it('OrgSettings hosts the External Keys pane after Federated Identities, where TEA is served', () => {
        const os = src('OrgSettings.vue')
        const fed = os.indexOf('<n-tab-pane name="federatedIdentities" tab="Federated Identities">')
        const ext = os.indexOf('<n-tab-pane name="externalKeys" tab="External Keys" v-if="teaProfilesAvailable(myUser?.installationType)">')
        expect(fed).toBeGreaterThan(0)
        expect(ext).toBeGreaterThan(fed)
        expect(os.slice(ext, os.indexOf('</n-tab-pane>', ext))).toContain('<ExternalKeysPanel :org-uuid="orgResolved" :notify="notify" :is-org-admin="isOrgAdmin" />')
        expect(os).toMatch(/const programmaticSubTab = ref<[^>]*'externalKeys'[^>]*>/)
    })

    it('the Scoped Keys table never lists an EXTERNAL key', () => {
        const os = src('OrgSettings.vue')
        const start = os.indexOf('const computedProgrammaticAccessKeys')
        const body = os.slice(start, os.indexOf('\n})\n', start))
        expect(body).toContain("k.type !== 'EXTERNAL'")
    })

    it('the shared apiKeys document asks for none of the Pro-only key fields, so CE keeps loading the tab', () => {
        const os = src('OrgSettings.vue')
        const start = os.indexOf('query apiKeys($orgUuid: ID!)')
        expect(start).toBeGreaterThan(0)
        const doc = os.slice(start, os.indexOf('`', start))
        expect(doc).toContain('apiKeys(orgUuid: $orgUuid)')
        for (const field of ['keyId', 'holderName', 'teaOrg']) expect(doc, field).not.toMatch(new RegExp('\\b' + field + '\\b'))
    })

    it('graphqlQueries holds the three EXTERNAL key documents', () => {
        const q = src('../utils/graphqlQueries.ts')
        for (const doc of ['EXTERNAL_API_KEYS', 'CREATE_EXTERNAL_API_KEY', 'SET_API_KEY_HOLDER_NAME']) {
            expect(q).toContain('const ' + doc + ' = gql`')
        }
        expect(q).toContain('externalApiKeys(orgUuid: $orgUuid)')
        expect(q).toContain('createExternalApiKey(orgUuid: $orgUuid, holderName: $holderName, notes: $notes)')
        expect(q).toContain('setApiKeyHolderName(apiKeyUuid: $apiKeyUuid, holderName: $holderName)')
    })

    // TEA-30 row 26: the Profiles tab, the overrides and package settings hosts, the Submissions tab and section.
    it('OrgSettings carries the SBOM Profiles sub-tab before Regulatory and Transparency Exchange API, and no other Profiles tab', () => {
        const os = src('OrgSettings.vue')
        const line = '<n-tab-pane name="sbomProfiles" tab="SBOM Profiles" v-if="teaProfilesAvailable(myUser.installationType)">'
        const profiles = os.indexOf(line)
        expect(profiles).toBeGreaterThan(os.indexOf('<n-tab-pane name="adminSettings"'))
        expect(profiles).toBeLessThan(os.indexOf('<n-tab-pane name="regulatory" tab="Regulatory"'))
        expect(profiles).toBeLessThan(os.indexOf('<n-tab-pane name="tea" tab="Transparency Exchange API"'))
        expect(os.slice(profiles, os.indexOf('</n-tab-pane>', profiles))).toContain('<SbomProfilesPanel')
        expect(os.slice(profiles, os.indexOf('</n-tab-pane>', profiles))).not.toContain('<PackageSettingsEditor')
        expect(os).not.toMatch(/const proOnlyTabs = \[[^\]]*'profiles'[^\]]*\]/)
        expect(os).not.toMatch(/const adminOnlyTabs = \[[^\]]*'profiles'[^\]]*\]/)
    })

    it('the perspective TEA dialog hosts the overrides and the package settings, wired to reload both catalogs', () => {
        const os = src('OrgSettings.vue')
        const card = os.search(/:title="'TEA profile of perspective: ' \+ selectedPerspectiveName"/)
        expect(card).toBeGreaterThan(0)
        const dialog = os.slice(card, os.indexOf('</n-card>', card))
        expect(dialog).toContain(":scope=\"selectedPerspectiveType === 'PRODUCT' ? 'COMPONENT' : 'PERSPECTIVE'\"")
        expect(dialog).toContain('ref="perspectiveTeaEditor"')
        expect(dialog.indexOf('<SbomProfileOverrides')).toBeGreaterThan(dialog.indexOf('<TeaProfileEditor'))
        expect(dialog).toContain('@changed="reloadPerspectiveSbomConsumers"')
        expect(dialog.indexOf('<PackageSettingsEditor')).toBeGreaterThan(dialog.indexOf('<SbomProfileOverrides'))
        expect(dialog).toContain('ref="perspectivePackageEditor"')
        expect(dialog).toContain('v-if="regulatoryPackagesAvailable(myUser.installationType)"')
        const fn = os.slice(os.indexOf('function reloadPerspectiveSbomConsumers'), os.indexOf('\n}\n', os.indexOf('function reloadPerspectiveSbomConsumers')))
        expect(fn).toContain('perspectiveTeaEditor.value?.reloadCatalog()')
        expect(fn).toContain('perspectivePackageEditor.value?.reloadCatalog()')
    })

    it('ComponentView hosts the TEA editor, the SBOM profile overrides and the package settings in their own tabs, in order', () => {
        const cv = src('ComponentView.vue')
        const paneOf = (line: string) => {
            const at = cv.indexOf(line)
            expect(at, line).toBeGreaterThan(0)
            return { at, body: cv.slice(at, cv.indexOf('</n-tab-pane>', at)) }
        }
        const tea = paneOf('<n-tab-pane name="tea" tab="TEA" v-if="teaProfilesAvailable(myUser.installationType)">')
        const sbom = paneOf('<n-tab-pane name="sbomProfiles" tab="SBOM Profiles" v-if="teaProfilesAvailable(myUser.installationType)">')
        const reg = paneOf('<n-tab-pane name="regulatory" tab="Regulatory" v-if="regulatoryPackagesAvailable(myUser.installationType)">')
        expect(sbom.at).toBeGreaterThan(tea.at)
        expect(reg.at).toBeGreaterThan(sbom.at)
        expect(reg.at).toBeLessThan(cv.indexOf('tab="Actions"'))
        // The TEA tab holds the editor alone.
        expect(tea.body).toContain('<TeaProfileEditor')
        expect(tea.body).toContain('ref="teaEditor"')
        expect(tea.body).not.toContain('<SbomProfileOverrides')
        expect(tea.body).not.toContain('<PackageSettingsEditor')
        expect(sbom.body).toContain('<h6>SBOM profiles at this {{ words.component }}</h6>')
        expect(sbom.body).toContain('<SbomProfileOverrides')
        expect(sbom.body).toContain('@changed="reloadSbomConsumers"')
        expect(sbom.body).not.toContain('<PackageSettingsEditor')
        expect(reg.body).toContain('<h6>Regulatory package settings</h6>')
        expect(reg.body).toContain('<PackageSettingsEditor')
        expect(reg.body).toContain('ref="packageEditor"')
        expect(reg.body).toContain('scope="COMPONENT"')
        // One of each in the page.
        for (const tag of ['<TeaProfileEditor', '<SbomProfileOverrides']) expect(cv.split(tag), tag).toHaveLength(2)
        const fn = cv.slice(cv.indexOf('function reloadSbomConsumers'), cv.indexOf('\n}\n', cv.indexOf('function reloadSbomConsumers')))
        expect(fn).toContain('teaEditor.value?.reloadCatalog()')
        expect(fn).toContain('packageEditor.value?.reloadCatalog()')
    })

    it('ComponentView settings: one tab per Save, in order: Core Settings, License & Supplier, Device, TEA, SBOM Profiles, Regulatory, then Actions', () => {
        const cv = src('ComponentView.vue')
        const lines = [
            '<n-tab-pane name="Core Settings">',
            '<n-tab-pane name="licenseSupplier" tab="License & Supplier">',
            '<n-tab-pane name="device" tab="Device" v-if="deviceWindowSupported">',
            '<n-tab-pane name="tea" tab="TEA"',
            '<n-tab-pane name="sbomProfiles" tab="SBOM Profiles"',
            '<n-tab-pane name="regulatory" tab="Regulatory"',
            '<n-tab-pane name="outputTriggers" tab="Actions"',
            '<n-tab-pane name="Input Events" tab="Rules"',
            '<n-tab-pane name="locks" tab="Locks"',
            '<n-tab-pane name="actionGuards" tab="Guards"',
            'name="Admin Settings">',
        ]
        const at = lines.map(l => cv.indexOf(l))
        for (const [i, l] of lines.entries()) expect(at[i], l).toBeGreaterThan(0)
        expect([...at].sort((a, b) => a - b)).toEqual(at)
        const core = cv.slice(at[0], cv.indexOf('</n-tab-pane>', at[0]))
        expect(core).toContain('class="coreSettingsActions"')
        for (const gone of ['component-license-block', 'Device class', 'Device support window']) expect(core, gone).not.toContain(gone)
    })

    it('ComponentView carries the Submissions tab after the branches tab, bound to the page branches and refresh token', () => {
        const cv = src('ComponentView.vue')
        const branches = cv.indexOf('<n-tab-pane name="branches"')
        const line = `<n-tab-pane v-if="componentData?.type === 'PRODUCT' && submissionsAvailable(myUser.installationType)" name="submissions" tab="Submissions">`
        const subs = cv.indexOf(line)
        expect(branches).toBeGreaterThan(0)
        expect(subs).toBeGreaterThan(branches)
        const pane = cv.slice(subs, cv.indexOf('</n-tab-pane>', subs))
        expect(pane).toContain('<ProductSubmissionsPanel')
        expect(pane).toContain(':branches="branches"')
        expect(pane).toContain(':refresh-token="latestRefreshToken"')
        expect(cv).toMatch(/\nconst branches: ComputedRef<any> = computed\(/)
        expect(cv).toMatch(/\nconst latestRefreshToken = ref\(0\)/)
        expect(src('../utils/componentPageTab.ts')).toContain("export const SUBMISSIONS_TAB: ComponentPageTab = 'submissions'")
    })

    it('ReleaseView carries the Regulatory submissions section after Generated artifacts and before Produced Deliverables, for the offered kinds', () => {
        const rv = src('ReleaseView.vue')
        const line = '<div class="container" v-if="submissionsAvailable(myUser?.installationType) && !isDocumentRound && regulatoryPackageKinds.length" data-testid="submissions-section">'
        const at = rv.indexOf(line)
        expect(at).toBeGreaterThan(rv.indexOf('<h3>Generated artifacts</h3>'))
        expect(at).toBeLessThan(rv.indexOf('Produced Deliverables'))
        expect(rv.slice(at + line.length).trimStart().startsWith('<h3>Regulatory submissions</h3>')).toBe(true)
        const block = rv.slice(at, rv.indexOf('</div>', at))
        expect(block).toContain('<ReleaseSubmissionsPanel')
        expect(block).toContain(':kinds="regulatoryPackageKinds"')
    })

    it('ReleaseView reads the offered kinds through their own Pro-gated query, never the shared release fragments', () => {
        const rv = src('ReleaseView.vue')
        const start = rv.indexOf('async function loadRegulatoryPackageKinds')
        expect(start).toBeGreaterThan(0)
        const load = rv.slice(start, rv.indexOf('\n}\n', start))
        const gate = load.indexOf('if (!submissionsAvailable(myUser?.installationType)) return')
        const query = load.indexOf('submissionQueries.ReleaseRegulatoryPackageKindsGql')
        expect(gate).toBeGreaterThan(0)
        expect(query).toBeGreaterThan(gate)
        expect(load).toContain('if (seq !== regulatoryKindsLoadSeq) return')
        expect(load).toContain("regulatoryPackageKinds.value = response.data?.release?.regulatoryPackageKinds ?? []")
        // Reset to [] on another release, so its block never shows the previous release's kinds.
        expect(load.indexOf('regulatoryPackageKinds.value = []')).toBeLessThan(gate)
        const fetch = rv.slice(rv.indexOf('async function fetchRelease ()'))
        expect(fetch.slice(0, fetch.indexOf('\n}\n'))).toContain('loadRegulatoryPackageKinds()')
        const q = src('../utils/graphqlQueries.ts')
        expect(q).not.toContain('regulatoryPackageKinds')
        expect(src('../utils/submissionQueries.ts')).toContain('release(releaseUuid: $releaseID, orgUuid: $orgID) { uuid regulatoryPackageKinds }')
    })

    it('ReleaseView shows the SBOM profile of each TEA entry in a Profile column after Name (design 3.3.7)', () => {
        const rv = src('ReleaseView.vue')
        expect(rv).toContain("    { key: 'name', title: 'Name' },\n    { key: 'profile', title: 'Profile' },\n    { key: 'mediaType', title: 'Media type' },")
    })

    it('ReleaseView renders a SUBMISSION History row through submissionHistoryText, before the acollection branch', () => {
        const rv = src('ReleaseView.vue')
        const branch = rv.indexOf("if (row.rus === 'SUBMISSION') {")
        expect(branch).toBeGreaterThan(rv.indexOf("if (row.rus === 'TEA_PUBLICATION') {"))
        expect(branch).toBeLessThan(rv.indexOf("if (row.source === 'acollection' && row.artifact) {"))
        const body = rv.slice(branch, rv.indexOf('\n            }\n', branch))
        expect(body).toContain('const txt = submissionHistoryText(row)')
        expect(body).toContain("return reasonIcon ? h('span', { style: 'display: inline-flex; align-items: center;' }, [txt, reasonIcon]) : txt")
    })

    // TEA-30 row 28: one download URL builder, and the dialog rule (design 3.8, D18).
    it('ReleaseView builds no download path; artifactDownload.ts is the one builder', () => {
        const rv = src('ReleaseView.vue')
        for (const literal of ["'/rawdownload'", "'/download'", '?version=']) expect(rv, literal).not.toContain(literal)
        expect(rv.split('/api/manual/v1/artifact/')).toHaveLength(2)
        const line = rv.split('\n').find(l => l.includes('/api/manual/v1/artifact/'))!
        expect(line).toContain("fetchWithAuth('/api/manual/v1/artifact/upload'")
        expect(rv).toContain("import { artifactDownloadUrl, downloadArtifactBytes, saveBlob } from '@/utils/artifactDownload'")
        const ad = src('../utils/artifactDownload.ts')
        expect(ad.split('/api/manual/v1/artifact/')).toHaveLength(2)
        expect(ad.split('?version=')).toHaveLength(2)
    })

    // The sixteen files of row 28: the fifteen non-spec files of 3.13 New (sbomProfileDelete.ts, the
    // shared delete path the editor, the catalog and the overrides call, among them) and
    // TeaProfileEditor.vue (a file added later is added here).
    const DIALOG_FILES = ['../utils/profilesQueries.ts', '../utils/submissionQueries.ts', '../utils/sbomProfile.ts',
        '../utils/packageSettings.ts', '../utils/submissions.ts', '../utils/submissionActs.ts', '../utils/artifactDownload.ts',
        '../utils/sbomProfileDelete.ts', 'SbomProfileEditor.vue', 'SbomProfilesPanel.vue', 'SbomProfileOverrides.vue',
        'SbomProfilePicker.vue', 'PackageSettingsEditor.vue', 'ProductSubmissionsPanel.vue', 'ReleaseSubmissionsPanel.vue',
        'TeaProfileEditor.vue']
    it.each(DIALOG_FILES)('%s: every Swal.fire is the object form, and no html sink', (file) => {
        const f = src(file)
        const calls = (f.match(/Swal\.fire\(/g) ?? []).length
        const objectCalls = (f.match(/Swal\.fire\(\s*\{/g) ?? []).length
        expect(objectCalls).toBe(calls)
        if (!f.includes("from 'sweetalert2'")) expect(calls).toBe(0)
        expect(f).not.toMatch(/\bhtml\s*:/)
        expect(f).not.toMatch(/v-html/)
        expect(f).not.toMatch(/innerHTML/)
    })

    it('the dialog rule covers the files that call Swal: the editor, the delete path, the package editor and the acts', () => {
        for (const file of ['TeaProfileEditor.vue', 'SbomProfileEditor.vue', '../utils/sbomProfileDelete.ts',
            'PackageSettingsEditor.vue', '../utils/submissionActs.ts']) {
            expect((src(file).match(/Swal\.fire\(\s*\{/g) ?? []).length, file).toBeGreaterThan(0)
        }
    })

    // Round 4 (design 3.7.1, D19): the text of a failed GraphQL call is graphqlRefusalText's, never
    // parseGraphQLError over Apollo's joined message.
    it.each(DIALOG_FILES)('%s: no parseGraphQLError call; the error text comes from graphqlRefusalText', (file) => {
        expect(src(file)).not.toContain('parseGraphQLError(')
    })

    it('the files that show a GraphQL error call graphqlRefusalText, and teaActErrorText ends in it', () => {
        for (const file of ['../utils/sbomProfileDelete.ts', 'SbomProfileEditor.vue', 'SbomProfilesPanel.vue',
            'SbomProfileOverrides.vue', 'PackageSettingsEditor.vue', 'ProductSubmissionsPanel.vue',
            'ReleaseSubmissionsPanel.vue', 'TeaProfileEditor.vue']) {
            expect(src(file), file).toContain('graphqlRefusalText(')
        }
        const tp = src('../utils/teaPublication.ts')
        expect(tp).toContain('return graphqlRefusalText(err)')
        expect(tp).not.toContain('parseGraphQLError(')
    })
})

