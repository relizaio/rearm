// @vitest-environment happy-dom
//
// The operator's pause and resume (RD2-17, sweep UI-37): the pause asks its reason in the page and sends
// nothing without one; the resume names what it lifts and acts on confirm only; no browser prompt.
import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { readFileSync } from 'fs'
import { join } from 'path'
import BoardPauseControl from './BoardPauseControl.vue'
import { pauseReasonToSend, resumeConfirmText } from '@/utils/agentBoardPause'

// The popover and the confirm teleport: render them in place, the confirm with a button that confirms.
const popover = { template: '<div class="pop"><slot name="trigger"/><slot/></div>' }
const confirm = { emits: ['positive-click'], template: '<div class="pc"><slot name="trigger"/><slot/><button class="pc__yes" @click="$emit(\'positive-click\')"/></div>' }
const stubs = { NPopover: popover, Popover: popover, NPopconfirm: confirm, Popconfirm: confirm }
const when = (at: string) => at.slice(0, 16).replace('T', ' ')
const paused = { pause: { level: 'OPERATOR', reason: 'release freeze', pausedBy: { kind: 'USER', name: 'pm@example.com' },
    pausedAt: '2026-09-27T10:00:00Z' } }

describe('the operator pause', () => {
    // happy-dom has no window.prompt, and vitest 5 refuses to spy on a missing property. Give it one so
    // the spy can show the browser prompt is never used.
    if (typeof window.prompt !== 'function') window.prompt = () => null
    const prompt = vi.spyOn(window, 'prompt')
    afterEach(() => prompt.mockClear())

    it('asks a reason in the page, sends nothing without one, and sends it trimmed', async () => {
        const w = mount(BoardPauseControl, { props: { action: 'pause', board: {}, when }, global: { stubs } })
        expect(w.find('[data-testid="pause-form"]').text()).toContain('Agents see this reason and stop taking new work.')
        const submit = w.find('[data-testid="pause-submit"]')
        expect(submit.attributes('disabled')).toBeDefined()
        await w.find('[data-testid="pause-reason"] textarea').setValue('   ')
        await submit.trigger('click')
        ;(w.vm as any).submit()  // and the handler itself refuses a blank reason, whatever calls it
        expect(w.emitted('pause')).toBeUndefined()

        await w.find('[data-testid="pause-reason"] textarea').setValue('  release freeze  ')
        expect(w.find('[data-testid="pause-submit"]').attributes('disabled')).toBeUndefined()
        await w.find('[data-testid="pause-submit"]').trigger('click')
        expect(w.emitted('pause')?.[0]?.[0]).toEqual({ reason: 'release freeze' })
        expect((w.vm as any).reason).toBe('', 'the form clears once sent')
        expect(prompt).not.toHaveBeenCalled()
    })

    it('cancels without sending', async () => {
        const w = mount(BoardPauseControl, { props: { action: 'pause', board: {}, when }, global: { stubs } })
        await w.find('[data-testid="pause-reason"] textarea').setValue('maybe')
        await w.find('[data-testid="pause-cancel"]').trigger('click')
        expect(w.emitted('pause')).toBeUndefined()
        expect((w.vm as any).reason).toBe('')
    })
})

describe('the operator resume', () => {
    it('names who paused it, when and why, and resumes on confirm only', async () => {
        const w = mount(BoardPauseControl, { props: { action: 'resume', board: paused, when }, global: { stubs } })
        expect(w.find('[data-testid="resume-confirm"]').text())
            .toBe('Resume the board? Paused by pm@example.com 2026-09-27 10:00: release freeze')
        await w.find('[data-testid="resume-open"]').trigger('click')
        expect(w.emitted('resume')).toBeUndefined()
        await w.find('.pc__yes').trigger('click')
        expect(w.emitted('resume')).toHaveLength(1)
    })

    it('words a pause with no holder or time', () => {
        expect(resumeConfirmText({ pause: { level: 'OPERATOR', reason: 'x' } }, when)).toBe('Resume the board? Paused: x')
        expect(resumeConfirmText({ pause: { level: 'OPERATOR' } }, when)).toBe('Resume the board?')
        expect(pauseReasonToSend(' a ')).toBe('a')
        expect(pauseReasonToSend('')).toBeNull()
    })
})

describe('the board page', () => {
    // happy-dom's import.meta.url is not a file URL: read the panel from the package root vitest runs in.
    const source = readFileSync(join(process.cwd(), 'src/components/AiAgentBoardsPanel.vue'), 'utf8')
    it('uses the in-page control for both, and no browser prompt', () => {
        expect(source).not.toContain('window.prompt')
        expect(source).toContain('action="pause"')
        expect(source).toContain('@pause="p => operatorPause(true, p.reason)"')
        expect(source).toContain('action="resume"')
        expect(source).toContain('@resume="operatorPause(false)"')
    })
})
