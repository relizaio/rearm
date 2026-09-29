// @vitest-environment happy-dom
//
// The board form's level ladder (task RD3-6): the rungs numbered by place from 0, added, removed and
// reordered, the ladder prompt, and the problems shown beside it. Mounted with a v-model parent, so what
// it edits is what the board form's save hands to ladderPatch.
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent, h, nextTick, ref } from 'vue'
import Editor from './AgentBoardLadderEditor.vue'
import { ladderDraftOf, LadderDraft, ladderPatch } from '@/utils/agentLadder'

const LADDER = { levels: [
    { number: 0, name: 'requirements', description: 'what the client asked for' },
    { number: 1, name: 'solution', description: null },
], prompt: null }

function host (initial: LadderDraft, error: string | null = null) {
    const draft = ref<LadderDraft>(initial)
    const w = mount(defineComponent({
        setup: () => () => h(Editor, { modelValue: draft.value, error, 'onUpdate:modelValue': (v: LadderDraft) => { draft.value = v } }),
    }))
    return { w, draft }
}

const names = (w: any) => w.findAll('[data-testid="ladder-name"] input').map((i: any) => (i.element as HTMLInputElement).value)
const numbers = (w: any) => w.findAll('[data-testid="ladder-number"]').map((n: any) => n.text())

describe('AgentBoardLadderEditor', () => {
    it('lists the rungs numbered by place from 0, with the prompt field', () => {
        const { w } = host(ladderDraftOf(LADDER))
        expect(numbers(w)).toEqual(['0', '1'])
        expect(names(w)).toEqual(['requirements', 'solution'])
        expect(w.find('[data-testid="ladder-prompt"]').exists()).toBe(true)
        expect(w.find('[data-testid="ladder-empty"]').exists()).toBe(false)
    })

    it('says a board without a ladder has no levels, and offers no prompt until a rung is added', () => {
        const { w } = host(ladderDraftOf(null))
        expect(w.find('[data-testid="ladder-empty"]').text()).toContain('carry no level')
        expect(w.find('[data-testid="ladder-prompt"]').exists()).toBe(false)
    })

    it('adds a level at the end, names it, and the patch sends the whole ladder', async () => {
        const { w, draft } = host(ladderDraftOf(LADDER))
        await w.find('[data-testid="ladder-add"]').trigger('click')
        expect(numbers(w)).toEqual(['0', '1', '2'])
        expect(w.find('[data-testid="ladder-problem"]').text()).toBe('Level 2 needs a name')
        await w.findAll('[data-testid="ladder-name"] input')[2].setValue('components')
        await nextTick()
        expect(w.find('[data-testid="ladder-problem"]').exists()).toBe(false)
        expect(ladderPatch(LADDER, draft.value)).toEqual({ levels: [
            { number: 0, name: 'requirements', description: 'what the client asked for' },
            { number: 1, name: 'solution', description: null },
            { number: 2, name: 'components', description: null }], prompt: null })
    })

    it('removes a level, renumbering the rest; removing every level removes the ladder (null)', async () => {
        const { w, draft } = host(ladderDraftOf(LADDER))
        await w.findAll('[data-testid="ladder-remove"]')[0].trigger('click')
        expect(names(w)).toEqual(['solution'])
        expect(numbers(w)).toEqual(['0'])
        expect(ladderPatch(LADDER, draft.value)).toEqual({ levels: [{ number: 0, name: 'solution', description: null }], prompt: null })
        await w.findAll('[data-testid="ladder-remove"]')[0].trigger('click')
        expect(w.find('[data-testid="ladder-empty"]').exists()).toBe(true)
        expect(ladderPatch(LADDER, draft.value)).toBeNull()
    })

    it('reorders levels, the first not up and the last not down', async () => {
        const { w, draft } = host(ladderDraftOf(LADDER))
        const up = w.findAll('[data-testid="ladder-up"]')
        const down = w.findAll('[data-testid="ladder-down"]')
        expect(up[0].attributes('disabled')).toBeDefined()
        expect(down[1].attributes('disabled')).toBeDefined()
        await up[1].trigger('click')
        expect(names(w)).toEqual(['solution', 'requirements'])
        expect(ladderPatch(LADDER, draft.value)?.levels.map(l => `${l.number} ${l.name}`)).toEqual(['0 solution', '1 requirements'])
    })

    it('sends the ladder prompt as typed, placeholders and all', async () => {
        const { w, draft } = host(ladderDraftOf(LADDER))
        await w.find('[data-testid="ladder-prompt"] textarea').setValue('Our rungs: {{levels}}; the last is {{last}}.')
        expect(ladderPatch(LADDER, draft.value)?.prompt).toBe('Our rungs: {{levels}}; the last is {{last}}.')
    })

    it('flags a name twice and stops adding at ten levels', async () => {
        const { w } = host({ levels: [{ name: 'a', description: '' }, { name: 'A', description: '' }], prompt: '' })
        expect(w.find('[data-testid="ladder-problem"]').text()).toBe("The ladder names level 'A' twice")
        const full = host({ levels: Array.from({ length: 10 }, (_, i) => ({ name: `l${i}`, description: '' })), prompt: '' })
        expect(full.w.find('[data-testid="ladder-add"]').attributes('disabled')).toBeDefined()
    })

    it('shows a save refusal about the ladder beside it', () => {
        const { w } = host(ladderDraftOf(LADDER), 'settings.ladder cannot be removed while 1 task(s) or group(s) carry a level')
        expect(w.find('[data-testid="board-ladder-error"]').text()).toContain('settings.ladder cannot be removed')
    })
})
