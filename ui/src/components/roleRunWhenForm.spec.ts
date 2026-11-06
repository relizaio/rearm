// @vitest-environment happy-dom
//
// The role form's "runs when" group, mounted (task TPF-156, T-111). AiAgentBoardsPanel.vue cannot be mounted for its
// role modal alone (the panel loads a board, its roles and its tasks), so a harness renders the form's block, copied
// here verbatim, over a reactive editingRole, with the form's own watch, also copied verbatim and run as written. The
// first row asserts both copies equal the panel's source, so a drift between this harness and the form fails.
import { afterEach, describe, expect, it } from 'vitest'
import { readFileSync } from 'fs'
import { resolve } from 'path'
import { defineComponent, nextTick, ref, watch } from 'vue'
import { mount } from '@vue/test-utils'
import { NDynamicTags, NSpace, NText } from 'naive-ui'
import { runWhenLocked } from '@/utils/agentRunWhen'

const BLOCK = `                    <!-- Runs when (task TPF-156, backend TPF-155): routing queues an OPTIONAL role only on a task whose tags meet the
                         condition; a REQUIRED role never carries one, so the two lists are cleared and disabled while necessity is
                         REQUIRED or the coordinator-checkpoint box is on (C13). The server lower-cases and checks the keys and refuses a
                         REQUIRED role, or the flagged role, with a condition. -->
                    <div data-testid="role-run-when">
                        <div class="flabel" style="margin-bottom: 4px">runs when</div>
                        <n-space :size="18" wrap>
                            <div>
                                <div class="flabel" style="margin-bottom: 2px">tagged any of</div>
                                <n-dynamic-tags v-model:value="editingRole.runWhenAny" :disabled="runWhenLocked(editingRole)"
                                                data-testid="role-run-when-any"/>
                            </div>
                            <div>
                                <div class="flabel" style="margin-bottom: 2px">tagged none of</div>
                                <n-dynamic-tags v-model:value="editingRole.runWhenNone" :disabled="runWhenLocked(editingRole)"
                                                data-testid="role-run-when-none"/>
                            </div>
                        </n-space>
                        <n-text depth="3" style="font-size: 11.5px; display: block; margin-top: 4px;">
                            Tag keys. Empty: the role runs on every task. Routing reads the task's tags when it reaches the role and
                            passes it over otherwise. To run it on a task anyway the coordinator names the role: task hold then task lifthold
                            --role while the task is queued, task authorize --role while it awaits the coordinator, task reopen --role --reason
                            once it is completed. OPTIONAL roles only, never the role carrying the coordinator checkpoint.
                        </n-text>
                    </div>
`

const WATCH = `watch(() => editingRole.value && runWhenLocked(editingRole.value), (locked) => {
    if (locked && editingRole.value) {
        editingRole.value.runWhenAny = []
        editingRole.value.runWhenNone = []
    }
})
`

const source = readFileSync(resolve(__dirname, 'AiAgentBoardsPanel.vue'), 'utf8')

function harness (role: any) {
    const editingRole = ref<any>(role)
    // The form's watch, run from its source text: (watch, editingRole, runWhenLocked) are the names it reads.
    new Function('watch', 'editingRole', 'runWhenLocked', WATCH)(watch, editingRole, runWhenLocked)
    const Harness = defineComponent({
        components: { NDynamicTags, NSpace, NText },
        setup: () => ({ editingRole, runWhenLocked }),
        template: '<div>' + BLOCK + '</div>',
    })
    const w = mount(Harness)
    mounted.push(w)
    return { w, editingRole }
}

const mounted: any[] = []
afterEach(() => { while (mounted.length) mounted.pop().unmount() })

const tags = (w: any, id: string) => w.findAllComponents(NDynamicTags).find((c: any) => c.attributes('data-testid') === id)
const disabled = (w: any, id: string) => tags(w, id)?.props('disabled')
const optional = (over: Record<string, any> = {}) => structuredClone({ name: 'mutant_coder', kind: 'AGENTIC', necessity: 'OPTIONAL',
    coordinatorCheckpoint: false, runWhenAny: [] as string[], runWhenNone: [] as string[], ...over })

async function typeChip (w: any, id: string, text: string) {
    const root = tags(w, id)
    await root.find('button').trigger('click')
    await nextTick()
    const input = root.find('input')
    await input.setValue(text)
    await input.trigger('keydown', { key: 'Enter' })
    await nextTick()
}

describe('the harness is the form', () => {
    it('renders the panel\'s block and runs the panel\'s watch, character for character', () => {
        expect(source).toContain(BLOCK)
        expect(source).toContain(WATCH)
        expect(BLOCK).toContain('data-testid="role-run-when-any"')
        expect(WATCH).toContain('runWhenLocked(editingRole.value)')
    })
})

describe('the role form: runs when, mounted', () => {
    it('OPTIONAL without the checkpoint: both lists enabled, typing adds a chip', async () => {
        const { w, editingRole } = harness(optional())
        expect(w.find('[data-testid="role-run-when"]').exists()).toBe(true)
        expect(disabled(w, 'role-run-when-any')).toBe(false)
        expect(disabled(w, 'role-run-when-none')).toBe(false)
        await typeChip(w, 'role-run-when-any', 'mutation')
        expect(editingRole.value.runWhenAny).toEqual(['mutation'])
        await typeChip(w, 'role-run-when-none', 'hotfix')
        expect(editingRole.value.runWhenNone).toEqual(['hotfix'])
        expect(w.text()).toContain('mutation')
    })

    it('REQUIRED clears both lists and disables both; OPTIONAL again re-enables them, still empty', async () => {
        const { w, editingRole } = harness(optional({ runWhenAny: ['mutation'], runWhenNone: ['hotfix'] }))
        expect(w.text()).toContain('mutation')
        editingRole.value.necessity = 'REQUIRED'
        await nextTick()
        await nextTick()
        expect(editingRole.value.runWhenAny).toEqual([])
        expect(editingRole.value.runWhenNone).toEqual([])
        expect(disabled(w, 'role-run-when-any')).toBe(true)
        expect(disabled(w, 'role-run-when-none')).toBe(true)
        expect(w.text()).not.toContain('mutation')
        editingRole.value.necessity = 'OPTIONAL'
        await nextTick()
        expect(disabled(w, 'role-run-when-any')).toBe(false)
        expect(disabled(w, 'role-run-when-none')).toBe(false)
        expect(editingRole.value.runWhenAny).toEqual([])
        expect(editingRole.value.runWhenNone).toEqual([])
    })

    it('ticking the coordinator checkpoint clears both lists and disables both; unticking re-enables them', async () => {
        const { w, editingRole } = harness(optional({ runWhenAny: ['mutation'], runWhenNone: ['hotfix'] }))
        editingRole.value.coordinatorCheckpoint = true
        await nextTick()
        await nextTick()
        expect(editingRole.value.runWhenAny).toEqual([])
        expect(editingRole.value.runWhenNone).toEqual([])
        expect(disabled(w, 'role-run-when-any')).toBe(true)
        expect(disabled(w, 'role-run-when-none')).toBe(true)
        editingRole.value.coordinatorCheckpoint = false
        await nextTick()
        expect(disabled(w, 'role-run-when-any')).toBe(false)
        expect(disabled(w, 'role-run-when-none')).toBe(false)
        expect(editingRole.value.runWhenAny).toEqual([])
    })

    it('a role opened REQUIRED renders both lists disabled', () => {
        const { w } = harness(optional({ necessity: 'REQUIRED' }))
        expect(disabled(w, 'role-run-when-any')).toBe(true)
        expect(disabled(w, 'role-run-when-none')).toBe(true)
    })
})
