import { describe, it, expect } from 'vitest'
import { organizationToCommit, supportInjectionFromSettings } from './orgSettingsCommit'

/**
 * The stale-toggle defect, end to end, at the level it actually occurred.
 *
 * Found by an operator running the walkthrough (board t20260909-061338-23148): the toggle read
 * OFF while the backend held ENABLED, verified over GraphQL. Nothing in the suite caught it,
 * because every piece worked -- the mutation sent the right value, the server stored it, the
 * local baseline advanced. The failure was in the SHAPE OF WHAT WAS PUT IN THE STORE, and it
 * only became visible on the NEXT hydration.
 *
 * So this test does not check any single function's return value. It replays the sequence:
 * save -> commit the mutation response -> re-render from the store -> read the toggle.
 */

/** The store mutation, verbatim from store.ts UPDATE_ORGANIZATION. */
function updateOrganization (state: any, organization: any) {
    state.organizations = state.organizations.filter((o: any) => o.uuid !== organization.uuid)
    state.organizations.push(organization)
}

const ORG = 'c839fa68-1b2c-4ba0-9292-f660e8f47184'

/**
 * What the server actually returns. supportInjection is DELIBERATELY ABSENT: selecting it
 * would make the mutation document invalid on a CE backend that does not declare the field.
 * That absence is the whole defect, so hard-coding it here is the point of the fixture.
 */
const mutationResponse = {
    uuid: ORG,
    name: 'Claude2',
    settings: {
        fdaPatchesMayCeaseStatement: 'patches statement seed',
        fdaRiskTransferProcessRef: 'DHF-SEED-2 rev B',
        fdaRiskIncreasesNotice: 'risk notice seed'
    }
}

function freshStore () {
    return { organizations: [{ uuid: ORG, name: 'Claude2', settings: { supportInjection: 'DISABLED' } }] }
}
const orgFromStore = (state: any) => state.organizations.find((o: any) => o.uuid === ORG)

describe('the support-injection toggle survives a save and a re-render', () => {
    it('still reads ON after saving ENABLED, with no reload', () => {
        const state = freshStore()

        // The operator flips the switch on and saves.
        updateOrganization(state, organizationToCommit(mutationResponse, true, true))

        // They navigate away and back: the page hydrates from the store, not from the form.
        expect(supportInjectionFromSettings(orgFromStore(state).settings)).toBe(true)
    })

    it('reads OFF again after saving DISABLED', () => {
        const state = freshStore()
        updateOrganization(state, organizationToCommit(mutationResponse, true, true))
        updateOrganization(state, organizationToCommit(mutationResponse, true, false))

        expect(supportInjectionFromSettings(orgFromStore(state).settings)).toBe(false)
    })

    /**
     * The second half of the defect, and the one that made it unrecoverable in the UI. The
     * mutation only sends supportInjection when it differs from the baseline the page hydrated.
     * With the field missing from the store the baseline was false, so after turning it ON the
     * operator could not turn it OFF: the form said OFF, the baseline said OFF, nothing
     * differed, and the field was never sent. Only a hard reload broke the loop.
     */
    it('leaves a DISABLED save sendable after an ENABLED one', () => {
        const state = freshStore()
        updateOrganization(state, organizationToCommit(mutationResponse, true, true))

        const hydratedBaseline = supportInjectionFromSettings(orgFromStore(state).settings)
        const operatorWantsOff = false
        expect(hydratedBaseline).not.toBe(operatorWantsOff)  // therefore the field IS sent
    })

    /**
     * CE has no such setting. Writing DISABLED into the store there would state a choice
     * nobody made, and the toggle is hidden on that build anyway.
     */
    it('adds nothing on a backend that does not support the field', () => {
        const committed = organizationToCommit(mutationResponse, false, true)
        expect(committed.settings.supportInjection).toBeUndefined()
        expect(committed).toBe(mutationResponse)
    })

    it('preserves the prose slots the mutation did return', () => {
        const committed = organizationToCommit(mutationResponse, true, true)
        expect(committed.settings.fdaRiskTransferProcessRef).toBe('DHF-SEED-2 rev B')
        expect(committed.settings.fdaPatchesMayCeaseStatement).toBe('patches statement seed')
    })
})
