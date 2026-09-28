// @vitest-environment happy-dom
//
// The operator's lock and unlock (RD2-17, sweep UI-37): the lock asks its reason in the page and sends
// nothing without one; the unlock names what it lifts and acts on confirm only; no browser prompt.
import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { readFileSync } from 'fs'
import { join } from 'path'
import BoardLockControl from './BoardLockControl.vue'
import { lockReasonToSend, unlockConfirmText } from '@/utils/agentBoardLock'

// The popover and the confirm teleport: render them in place, the confirm with a button that confirms.
const popover = { template: '<div class="pop"><slot name="trigger"/><slot/></div>' }
const confirm = { emits: ['positive-click'], template: '<div class="pc"><slot name="trigger"/><slot/><button class="pc__yes" @click="$emit(\'positive-click\')"/></div>' }
const stubs = { NPopover: popover, Popover: popover, NPopconfirm: confirm, Popconfirm: confirm }
const when = (at: string) => at.slice(0, 16).replace('T', ' ')
const locked = { lock: { level: 'OPERATOR', reason: 'release freeze', lockedBy: { kind: 'USER', name: 'pm@example.com' },
    lockedAt: '2026-09-27T10:00:00Z' } }

describe('the operator lock', () => {
    const prompt = vi.spyOn(window, 'prompt')
    afterEach(() => prompt.mockClear())

    it('asks a reason in the page, sends nothing without one, and sends it trimmed', async () => {
        const w = mount(BoardLockControl, { props: { action: 'lock', board: {}, when }, global: { stubs } })
        expect(w.find('[data-testid="lock-form"]').text()).toContain('Agents see this reason and stop taking new work.')
        const submit = w.find('[data-testid="lock-submit"]')
        expect(submit.attributes('disabled')).toBeDefined()
        await w.find('[data-testid="lock-reason"] textarea').setValue('   ')
        await submit.trigger('click')
        ;(w.vm as any).submit()  // and the handler itself refuses a blank reason, whatever calls it
        expect(w.emitted('lock')).toBeUndefined()

        await w.find('[data-testid="lock-reason"] textarea').setValue('  release freeze  ')
        expect(w.find('[data-testid="lock-submit"]').attributes('disabled')).toBeUndefined()
        await w.find('[data-testid="lock-submit"]').trigger('click')
        expect(w.emitted('lock')?.[0]?.[0]).toEqual({ reason: 'release freeze' })
        expect((w.vm as any).reason).toBe('', 'the form clears once sent')
        expect(prompt).not.toHaveBeenCalled()
    })

    it('cancels without sending', async () => {
        const w = mount(BoardLockControl, { props: { action: 'lock', board: {}, when }, global: { stubs } })
        await w.find('[data-testid="lock-reason"] textarea').setValue('maybe')
        await w.find('[data-testid="lock-cancel"]').trigger('click')
        expect(w.emitted('lock')).toBeUndefined()
        expect((w.vm as any).reason).toBe('')
    })
})

describe('the operator unlock', () => {
    it('names who locked it, when and why, and unlocks on confirm only', async () => {
        const w = mount(BoardLockControl, { props: { action: 'unlock', board: locked, when }, global: { stubs } })
        expect(w.find('[data-testid="unlock-confirm"]').text())
            .toBe('Unlock the board? Locked by pm@example.com 2026-09-27 10:00: release freeze')
        await w.find('[data-testid="unlock-open"]').trigger('click')
        expect(w.emitted('unlock')).toBeUndefined()
        await w.find('.pc__yes').trigger('click')
        expect(w.emitted('unlock')).toHaveLength(1)
    })

    it('words a lock with no holder or time', () => {
        expect(unlockConfirmText({ lock: { level: 'OPERATOR', reason: 'x' } }, when)).toBe('Unlock the board? Locked: x')
        expect(unlockConfirmText({ lock: { level: 'OPERATOR' } }, when)).toBe('Unlock the board?')
        expect(lockReasonToSend(' a ')).toBe('a')
        expect(lockReasonToSend('')).toBeNull()
    })
})

describe('the board page', () => {
    // happy-dom's import.meta.url is not a file URL: read the panel from the package root vitest runs in.
    const source = readFileSync(join(process.cwd(), 'src/components/AiAgentBoardsPanel.vue'), 'utf8')
    it('uses the in-page control for both, and no browser prompt', () => {
        expect(source).not.toContain('window.prompt')
        expect(source).toContain('action="lock"')
        expect(source).toContain('@lock="p => operatorLock(true, p.reason)"')
        expect(source).toContain('action="unlock"')
        expect(source).toContain('@unlock="operatorLock(false)"')
    })
})
