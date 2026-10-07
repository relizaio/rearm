import { computed, ref, type ComputedRef, type Ref } from 'vue'

/**
 * The Export Release BOM dialog's score state (SCORE-6, design 3.7 and D-10), out of
 * ReleaseView.vue so a test can hold it: the snapshot of the options a score was asked with,
 * the "Options changed; score again" line, Score disabled while a score is pending, and the
 * key that mounts a fresh SbomScorePanel on each click. The panel owns the request itself.
 */
export interface ReleaseSbomScore {
    /** A score was asked for since the dialog opened: the panel is shown. */
    requested: Ref<boolean>
    /** The panel's v-model:pending. */
    pending: Ref<boolean>
    /** The panel's key; each Score click bumps it, so the panel mounts afresh and scores again. */
    run: Ref<number>
    /** The form's values differ from those of the score on show. */
    optionsChanged: ComputedRef<boolean>
    /** The Score button's disabled state: not a JSON SBOM export, or a score is pending. */
    buttonDisabled: ComputedRef<boolean>
    /** The variables of the score on show, for its query (and its Retry), whatever the form says now. */
    scoredVariables: () => Record<string, any>
    /** Score the form as it is now: snapshot its options and mount a fresh panel. Ignored while disabled. */
    score: () => void
    /** The dialog was reopened: no panel until the next Score. */
    reset: () => void
}

export interface ReleaseSbomScoreDeps {
    /** The score query's variables for the form as it is now (buildReleaseScoreVariables). */
    variables: () => Record<string, any>
    /** The form is not on a JSON SBOM export. */
    formDisabled: () => boolean
}

export function useReleaseSbomScore (deps: ReleaseSbomScoreDeps): ReleaseSbomScore {
    const requested = ref(false)
    const pending = ref(false)
    const run = ref(0)
    // As JSON: compared with the form's current variables, and parsed afresh for each query.
    const snapshot = ref('')

    const optionsChanged = computed((): boolean =>
        requested.value && JSON.stringify(deps.variables()) !== snapshot.value)

    const buttonDisabled = computed((): boolean => deps.formDisabled() || pending.value)

    function score (): void {
        if (buttonDisabled.value) return
        snapshot.value = JSON.stringify(deps.variables())
        requested.value = true
        run.value++
    }

    function reset (): void {
        requested.value = false
    }

    return {
        requested,
        pending,
        run,
        optionsChanged,
        buttonDisabled,
        scoredVariables: () => JSON.parse(snapshot.value),
        score,
        reset
    }
}
