import { describe, expect, it } from 'vitest'
import { PROGRAMMATIC_SUBTABS, PROGRAMMATIC_TAB, resolveOrgSettingsTab } from './orgSettingsTabs'

// API Key Groups moved under Programmatic Access, as Free Form Keys did before it: an old link lands on the sub-tab.
describe('the Organization Settings tab a link lands on', () => {
    it('sends ?tab=apiKeyGroups to Programmatic Access with API Key Groups selected', () => {
        expect(resolveOrgSettingsTab('apiKeyGroups')).toEqual({ tab: 'programmaticAccess', programmaticSubTab: 'apiKeyGroups' })
    })

    it('still sends ?tab=freeFormKeys to Programmatic Access with Free Form Keys selected', () => {
        expect(resolveOrgSettingsTab('freeFormKeys')).toEqual({ tab: PROGRAMMATIC_TAB, programmaticSubTab: 'freeFormKeys' })
    })

    it('leaves every other tab, Programmatic Access itself included, as asked and its sub-tab alone', () => {
        for (const t of ['programmaticAccess', 'integrations', 'users', 'userGroups', 'teams', 'adminSettings', 'userKeys', '']) {
            expect(resolveOrgSettingsTab(t), t).toEqual({ tab: t, programmaticSubTab: null })
        }
    })

    it('lists API Key Groups right after Free Form Keys', () => {
        expect([...PROGRAMMATIC_SUBTABS]).toEqual(['freeFormKeys', 'apiKeyGroups', 'userKeys', 'scopedKeys', 'federatedIdentities', 'externalKeys'])
    })
})
