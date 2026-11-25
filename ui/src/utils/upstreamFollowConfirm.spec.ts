// T-521, T-522 and T-523 (TPF-185 design 3.8, threat model rows 6 and 7): the pure model of the
// confirmation page. The starting point is sent only when it is complete and only with the field
// of its kind; the Follow button is blocked until everything is chosen and the newest preview
// belongs to the values in the form; the confirm carries the very depth and starting point the
// preview was asked for.
import { describe, expect, it } from 'vitest'
import {
    ADOPT_REQUIRED, BackfillKind, DEPTH_REQUIRED, DriftBehaviour, EXPIRED, ItemTarget, NAME_REQUIRED, NAME_TOO_LONG, NOT_READY, PREVIEW_PENDING,
    buildBackfillInput, buildConfirmInput, componentAdoptRequired, confirmBlockers, currentScopeKey, dayStartUtc, initialConfirmModel,
    overLimitSentence, scopeKey, scopeVariables,
} from './upstreamFollowConfirm'
import type { BackfillModel, ConfirmModel, ScopeState } from './upstreamFollowConfirm'
import { startingPointRequired } from './upstreamLabels'

const PREP = 'aaaaaaaa-1111-4111-8111-000000000001'
const REL = 'bbbbbbbb-2222-4222-8222-000000000002'
const CAND = 'cccccccc-3333-4333-8333-000000000003'
const NOW = new Date('2026-11-25T10:00:00Z')

function proposal (upstreamUuid: string, upstreamName: string, proposedDepth: number, extra: Record<string, any> = {}) {
    return {
        kind: 'COMPONENT', upstreamUuid, upstreamName, rearmName: upstreamName, nameCollides: false, existingItem: null,
        adoptionCandidates: [], releases: 10, finalReleases: 9, preReleases: 1, releasesNotStatingPreRelease: 0, releasesWithDate: 10,
        releasesWithUnusableVersion: 0, preReleaseRecognition: 'STATED', proposedDepth, depthBasis: 'HISTORY',
        depths: [0, 1, 2, 3, 4].map(depth => ({ depth, fit: 'FITS', lineCount: depth, unplaceable: 0, linesTruncated: false, lines: [] })),
        failure: null, ...extra,
    }
}

function preparation (extra: Record<string, any> = {}) {
    return {
        uuid: PREP, org: 'o', source: 's', state: 'READY', failure: null, sourceFailure: null, message: null,
        createdDate: '2026-11-25T09:00:00Z', finishedDate: '2026-11-25T09:01:00Z', expires: '2026-11-25T10:15:00Z',
        componentsTruncated: false, maxBackfillReleases: 500,
        item: proposal('dddddddd-0000-4000-8000-000000000000', 'Acme Suite', 2, { kind: 'PRODUCT', rearmName: 'Acme Suite (supplier)', nameCollides: true }),
        components: [
            proposal('eeeeeeee-0000-4000-8000-000000000001', 'acme-core', 1, { adoptionCandidates: [{ component: CAND, name: 'acme-core', releases: 12, releasesWithUpstreamVersion: 9 }] }),
            proposal('eeeeeeee-0000-4000-8000-000000000002', 'acme-ui', 3),
        ],
        ...extra,
    }
}

const backfill = (b: Partial<BackfillModel>): Pick<ConfirmModel, 'backfill'> => ({ backfill: { kind: null, count: null, day: null, release: null, ...b } })

/** A model with everything chosen, and the scope answer that belongs to it. */
function complete () {
    const prep = preparation()
    const model = initialConfirmModel(prep)
    model.backfill = { kind: BackfillKind.LATEST_PER_LINE, count: 3, day: null, release: null }
    const scope: ScopeState = { key: currentScopeKey(model, prep), answer: { releases: 12, withinLimit: true }, error: null }
    return { prep, model, scope }
}

describe('buildBackfillInput (T-521)', () => {
    it('isNullWithoutAKind', () => {
        expect(buildBackfillInput(backfill({}))).toBeNull()
    })

    it.each([[null], [0], [51], [2.5]])('isNullForTheNewestReleasesOfEachLineWithCount %s', (count) => {
        expect(buildBackfillInput(backfill({ kind: BackfillKind.LATEST_PER_LINE, count: count as any }))).toBeNull()
    })

    it('takesTheNewestThreeReleasesOfEachLine', () => {
        expect(buildBackfillInput(backfill({ kind: BackfillKind.LATEST_PER_LINE, count: 3 }))).toEqual({ kind: 'LATEST_PER_LINE', count: 3 })
        expect(buildBackfillInput(backfill({ kind: BackfillKind.LATEST_PER_LINE, count: 1 }))).toEqual({ kind: 'LATEST_PER_LINE', count: 1 })
        expect(buildBackfillInput(backfill({ kind: BackfillKind.LATEST_PER_LINE, count: 50 }))).toEqual({ kind: 'LATEST_PER_LINE', count: 50 })
    })

    it('sendsADayAsTheUtcStartOfThatDay', () => {
        expect(buildBackfillInput(backfill({ kind: BackfillKind.FROM_DATE }))).toBeNull()
        expect(buildBackfillInput(backfill({ kind: BackfillKind.FROM_DATE, day: '2026-03-01' }))).toEqual({ kind: 'FROM_DATE', date: '2026-03-01T00:00:00.000Z' })
    })

    it('refusesADayThatIsNotADay', () => {
        for (const day of ['2026-02-30', '2026-3-1', '01.03.2026', '']) expect(dayStartUtc(day)).toBeNull()
    })

    it('takesAPickedRelease', () => {
        expect(buildBackfillInput(backfill({ kind: BackfillKind.FROM_RELEASE }))).toBeNull()
        expect(buildBackfillInput(backfill({ kind: BackfillKind.FROM_RELEASE, release: REL }))).toEqual({ kind: 'FROM_RELEASE', release: REL })
    })

    it('neverMixesTheFieldsOfTwoKinds', () => {
        const input = buildBackfillInput(backfill({ kind: BackfillKind.FROM_DATE, count: 3, release: REL, day: '2026-03-01' }))
        expect(input).toEqual({ kind: 'FROM_DATE', date: '2026-03-01T00:00:00.000Z' })
        expect('count' in input!).toBe(false)
        expect('release' in input!).toBe(false)
    })
})

describe('initialConfirmModel and confirmBlockers (T-522)', () => {
    it('startsWithTheProposalsStopAndNoStartingPoint', () => {
        const prep = preparation()
        const model = initialConfirmModel(prep)
        expect(model.backfill.kind).toBeNull()
        expect(model.onDrift).toBe(DriftBehaviour.STOP)
        expect(model.target).toBe(ItemTarget.CREATE)
        expect(model.name).toBe('Acme Suite (supplier)')
        expect(model.depth).toBe(2)
        expect(model.components.map(c => c.depth)).toEqual([1, 3])
        expect(model.components.every(c => c.target === ItemTarget.CREATE && c.adopt === null)).toBe(true)
        const blockers = confirmBlockers(model, prep, { key: null, answer: null, error: null }, NOW)
        expect(blockers).toContain(startingPointRequired)
        expect(blockers).not.toContain(PREVIEW_PENDING)
    })

    it('hasNoBlockerWhenEverythingIsChosenAndThePreviewIsCurrentAndWithinTheLimit', () => {
        const { prep, model, scope } = complete()
        expect(confirmBlockers(model, prep, scope, NOW)).toEqual([])
    })

    it('isBlockedWhileThePreparationIsPending', () => {
        const { prep, model, scope } = complete()
        expect(confirmBlockers(model, { ...prep, state: 'PENDING' }, scope, NOW)).toContain(NOT_READY)
    })

    it('isBlockedOnceThePreparationExpired', () => {
        const { prep, model, scope } = complete()
        expect(confirmBlockers(model, prep, scope, new Date('2026-11-25T10:15:00Z'))).toContain(EXPIRED)
        expect(confirmBlockers(model, prep, scope, new Date('2026-11-25T10:14:59Z'))).toEqual([])
    })

    it('isBlockedByANameOfSpacesOrOverAHundredCharacters', () => {
        const { prep, model, scope } = complete()
        expect(confirmBlockers({ ...model, name: '   ' }, prep, scope, NOW)).toContain(NAME_REQUIRED)
        expect(confirmBlockers({ ...model, name: 'x'.repeat(101) }, prep, scope, NOW)).toContain(NAME_TOO_LONG)
        expect(confirmBlockers({ ...model, name: 'x'.repeat(100) }, prep, scope, NOW)).toEqual([])
    })

    it('isBlockedByAnAdoptionWithoutACandidate', () => {
        const { prep, model, scope } = complete()
        expect(confirmBlockers({ ...model, target: ItemTarget.ADOPT, adopt: null }, prep, scope, NOW)).toContain(ADOPT_REQUIRED)
        expect(confirmBlockers({ ...model, target: ItemTarget.ADOPT, adopt: CAND }, prep, scope, NOW)).toEqual([])
        const components = model.components.map((c, i) => i === 0 ? { ...c, target: ItemTarget.ADOPT, adopt: null } : c)
        expect(confirmBlockers({ ...model, components }, prep, scope, NOW)).toContain(componentAdoptRequired('acme-core'))
    })

    it('isBlockedWithoutADepth', () => {
        const { prep, model, scope } = complete()
        expect(confirmBlockers({ ...model, depth: null }, prep, scope, NOW)).toContain(DEPTH_REQUIRED)
    })

    it('waitsForThePreviewWhenACompleteStartingPointHasNoAnswerYet', () => {
        const { prep, model } = complete()
        expect(confirmBlockers(model, prep, { key: null, answer: null, error: null }, NOW)).toEqual([PREVIEW_PENDING])
    })

    it('waitsForThePreviewWhenTheAnswerBelongsToAnotherDepth', () => {
        const { prep, model, scope } = complete()
        expect(confirmBlockers({ ...model, depth: 1 }, prep, scope, NOW)).toEqual([PREVIEW_PENDING])
        const other = { ...model, backfill: { ...model.backfill, count: 4 } }
        expect(confirmBlockers(other, prep, scope, NOW)).toEqual([PREVIEW_PENDING])
    })

    it('isBlockedByACurrentAnswerOverTheLimitWithBothNumbers', () => {
        const { prep, model, scope } = complete()
        const over = { ...scope, answer: { releases: 812, withinLimit: false } }
        const blockers = confirmBlockers(model, prep, over, NOW)
        expect(blockers).toEqual([overLimitSentence(812, 500)])
        expect(blockers[0]).toBe('This starting point takes in 812 releases. At most 500 can be followed at once: choose a later starting point.')
    })

    it('isBlockedByARefusedPreviewForTheCurrentValues', () => {
        const { prep, model, scope } = complete()
        expect(confirmBlockers(model, prep, { key: scope.key, answer: null, error: 'The backfill point is not valid.' }, NOW))
            .toEqual(['The backfill point is not valid.'])
    })

    it('takesTheStandingDepthOfAnItemAlreadyInReARM', () => {
        const prep = preparation()
        prep.item.existingItem = { uuid: 'i', component: CAND, depth: 3, depthOrigin: 'CHOSEN', updateState: 'UPDATED' }
        const model = initialConfirmModel(prep)
        expect(model.depth).toBe(3)
        model.backfill = { kind: BackfillKind.LATEST_PER_LINE, count: 3, day: null, release: null }
        model.name = ''
        model.depth = 0
        const scope: ScopeState = { key: scopeKey(3, buildBackfillInput(model)), answer: { releases: 1, withinLimit: true }, error: null }
        expect(confirmBlockers(model, prep, scope, NOW)).toEqual([])
    })
})

describe('buildConfirmInput (T-523)', () => {
    it('carriesTheDepthAndStartingPointOfTheScopeQueryAndNothingUnchanged', () => {
        const { prep, model } = complete()
        model.name = prep.item.rearmName
        const input = buildConfirmInput(model, prep)
        const scope = scopeVariables(model, prep)!
        expect(input.depth).toEqual(scope.depth)
        expect(input.backfill).toEqual(scope.backfill)
        expect(scopeKey(input.depth, input.backfill)).toBe(scopeKey(scope.depth, scope.backfill))
        expect(scope.preparation).toBe(PREP)
        expect(input).toEqual({ depth: 2, backfill: { kind: 'LATEST_PER_LINE', count: 3 }, onDrift: 'STOP' })
    })

    it('sendsAChangedNameTrimmed', () => {
        const { prep, model } = complete()
        expect(buildConfirmInput({ ...model, name: '  Acme  ' }, prep).name).toBe('Acme')
    })

    it('sendsTheAdoptedCandidateAndNoName', () => {
        const { prep, model } = complete()
        const input = buildConfirmInput({ ...model, target: ItemTarget.ADOPT, adopt: CAND, name: 'Other' }, prep)
        expect(input.adopt).toBe(CAND)
        expect('name' in input).toBe(false)
    })

    it('sendsOnlyTheComponentsThatChangedWithOnlyTheirChangedKeys', () => {
        const prep = preparation()
        prep.components.push(proposal('eeeeeeee-0000-4000-8000-000000000003', 'acme-cli', 2))
        const model = initialConfirmModel(prep)
        model.backfill = { kind: BackfillKind.LATEST_PER_LINE, count: 3, day: null, release: null }
        model.components[0] = { ...model.components[0], target: ItemTarget.ADOPT, adopt: CAND }
        model.components[1] = { ...model.components[1], depth: 1 }
        expect(buildConfirmInput(model, prep).components).toEqual([
            { upstreamUuid: 'eeeeeeee-0000-4000-8000-000000000001', adopt: CAND },
            { upstreamUuid: 'eeeeeeee-0000-4000-8000-000000000002', depth: 1 },
        ])
    })

    it('sendsNoComponentsKeyWhenNothingChanged', () => {
        const { prep, model } = complete()
        expect('components' in buildConfirmInput(model, prep)).toBe(false)
    })

    it('sendsNothingForAComponentThatFailedOrIsAlreadyInReARM', () => {
        const prep = preparation()
        prep.components[0].failure = 'SOURCE_FAILURE'
        prep.components[1].existingItem = { uuid: 'i', component: CAND, depth: 3, depthOrigin: 'CHOSEN', updateState: 'UPDATED' }
        const model = initialConfirmModel(prep)
        model.backfill = { kind: BackfillKind.LATEST_PER_LINE, count: 3, day: null, release: null }
        model.components = model.components.map(c => ({ ...c, depth: 4, target: ItemTarget.ADOPT, adopt: CAND }))
        expect('components' in buildConfirmInput(model, prep)).toBe(false)
    })

    it('sendsTheStandingDepthAndNoNameOrAdoptionForAnItemAlreadyInReARM', () => {
        const prep = preparation()
        prep.item.existingItem = { uuid: 'i', component: CAND, depth: 3, depthOrigin: 'CHOSEN', updateState: 'UPDATED' }
        const model = initialConfirmModel(prep)
        model.backfill = { kind: BackfillKind.FROM_RELEASE, count: null, day: null, release: REL }
        model.depth = 1
        model.name = 'Changed'
        const input = buildConfirmInput(model, prep)
        expect(input.depth).toBe(3)
        expect(input.backfill).toEqual({ kind: 'FROM_RELEASE', release: REL })
        expect('name' in input).toBe(false)
        expect('adopt' in input).toBe(false)
        expect(buildConfirmInput({ ...model, target: ItemTarget.ADOPT, adopt: CAND }, prep).adopt).toBeUndefined()
    })

    it('sendsTheChosenBehaviourOnDrift', () => {
        const { prep, model } = complete()
        expect(buildConfirmInput({ ...model, onDrift: DriftBehaviour.ADAPT }, prep).onDrift).toBe('ADAPT')
    })
})

describe('scopeKey', () => {
    it('isTheSameStringExactlyForTheSameValues', () => {
        const a = { kind: BackfillKind.LATEST_PER_LINE, count: 3 } as const
        expect(scopeKey(2, a)).toBe(scopeKey(2, { kind: BackfillKind.LATEST_PER_LINE, count: 3 }))
        expect(scopeKey(2, a)).not.toBe(scopeKey(1, a))
        expect(scopeKey(2, a)).not.toBe(scopeKey(2, { kind: BackfillKind.LATEST_PER_LINE, count: 4 }))
        expect(scopeKey(2, { kind: BackfillKind.FROM_RELEASE, release: REL })).not.toBe(scopeKey(2, { kind: BackfillKind.FROM_DATE, date: REL }))
    })
})
