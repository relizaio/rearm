import { describe, expect, it } from 'vitest'
import { ref } from 'vue'
import { useReleaseSbomScore } from './useReleaseSbomScore'

/**
 * The Export Release BOM dialog's score state (SCORE-6, design 3.7, D-10): what ReleaseView
 * binds to the Score button, the "Options changed; score again" line and the panel's key.
 */
function setup () {
    const form = ref({ release: 'r1', tldOnly: true, ignoreDev: false })
    const formDisabled = ref(false)
    const bomType = ref('SBOM')
    const score = useReleaseSbomScore({
        variables: () => ({ ...form.value, profiles: ['cisa-2026', 'fda'] }),
        formDisabled: () => formDisabled.value,
        sbomForm: () => bomType.value === 'SBOM'
    })
    return { form, formDisabled, bomType, score }
}

describe('useReleaseSbomScore', () => {
    it('shows no panel and no options line before the first score', () => {
        const { form, score } = setup()
        expect(score.requested.value).toBe(false)
        expect(score.run.value).toBe(0)
        form.value = { ...form.value, tldOnly: false }
        expect(score.optionsChanged.value).toBe(false)
    })

    it('says the options changed when the form moves after a score, and not when it moves back (D-10)', () => {
        const { form, score } = setup()
        score.score()
        expect(score.requested.value).toBe(true)
        expect(score.optionsChanged.value).toBe(false)

        form.value = { ...form.value, tldOnly: false }
        expect(score.optionsChanged.value).toBe(true)
        form.value = { ...form.value, tldOnly: true }
        expect(score.optionsChanged.value).toBe(false)
        form.value = { ...form.value, ignoreDev: true }
        expect(score.optionsChanged.value).toBe(true)
    })

    it('scores the options of the click: a new score takes a new snapshot and clears the line', () => {
        const { form, score } = setup()
        score.score()
        form.value = { ...form.value, tldOnly: false }
        // The query (and its Retry) send the scored options, not the form's current ones.
        expect(score.scoredVariables()).toEqual({ release: 'r1', tldOnly: true, ignoreDev: false, profiles: ['cisa-2026', 'fda'] })

        score.score()
        expect(score.optionsChanged.value).toBe(false)
        expect(score.scoredVariables().tldOnly).toBe(false)
    })

    it('bumps the panel key on every click, so each click mounts a fresh panel and scores again', () => {
        const { score } = setup()
        score.score()
        expect(score.run.value).toBe(1)
        score.score()
        expect(score.run.value).toBe(2)
    })

    it('disables Score while a score is pending, and ignores a click then (design 3.7)', () => {
        const { score } = setup()
        score.score()
        score.pending.value = true
        expect(score.buttonDisabled.value).toBe(true)
        score.score()
        expect(score.run.value).toBe(1)

        score.pending.value = false
        expect(score.buttonDisabled.value).toBe(false)
        score.score()
        expect(score.run.value).toBe(2)
    })

    it('disables Score for anything but a JSON SBOM export, and ignores a click then', () => {
        const { formDisabled, score } = setup()
        formDisabled.value = true
        expect(score.buttonDisabled.value).toBe(true)
        score.score()
        expect(score.requested.value).toBe(false)
        expect(score.run.value).toBe(0)
        formDisabled.value = false
        expect(score.buttonDisabled.value).toBe(false)
    })

    it('hides the panel on another BOM type without dropping it, and shows the same score back on SBOM (D-10)', () => {
        const { bomType, score } = setup()
        expect(score.shown.value).toBe(false)
        score.score()
        expect(score.shown.value).toBe(true)

        bomType.value = 'OBOM'
        expect(score.shown.value).toBe(false)
        expect(score.requested.value).toBe(true)
        bomType.value = 'SBOM'
        // Same panel key and snapshot: nothing remounts, so nothing is sent without a click.
        expect(score.shown.value).toBe(true)
        expect(score.run.value).toBe(1)
        expect(score.optionsChanged.value).toBe(false)
    })

    it('drops the panel and the options line when the dialog is reopened', () => {
        const { form, score } = setup()
        score.score()
        form.value = { ...form.value, tldOnly: false }
        score.reset()
        expect(score.requested.value).toBe(false)
        expect(score.shown.value).toBe(false)
        expect(score.optionsChanged.value).toBe(false)
    })
})
