import { describe, expect, it } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { parse as parseSfc } from '@vue/compiler-sfc'
import { BOARD_SETTING_KEYS, settingsDraftOf, settingsPatch } from '@/utils/agentBudget'

// humanQueueAgeMinutes in the board settings form (task 28dc4afb). The panel is not mounted here
// (it loads a board, its roles and its tasks); the form's field is read from the template the build
// ships, and what it saves from settingsDraftOf, which the panel's save sends through settingsPatch.
const source = readFileSync(fileURLToPath(new URL('./AiAgentBoardsPanel.vue', import.meta.url)), 'utf8')
const template = parseSfc(source).descriptor.template?.content ?? ''

describe('the board settings form: notify a person after', () => {
    it('binds the field to the board\'s humanQueueAgeMinutes, between the stops and the event retention', () => {
        const field = template.indexOf('data-testid="board-human-queue-age"')
        expect(field).toBeGreaterThan(-1)
        const tag = template.lastIndexOf('<n-input-number', field)
        expect(template.slice(tag, field)).toContain('v-model:value="editingBoard.humanQueueAgeMinutes"')
        expect(template.indexOf('editingBoard.noProgressRepeatsToStop')).toBeLessThan(field)
        expect(template.indexOf('editingBoard.eventRetentionDays')).toBeGreaterThan(field)
    })

    it('is a board setting the form saves', () => {
        expect(BOARD_SETTING_KEYS).toContain('humanQueueAgeMinutes')
        expect(source).toContain('settingsPatch(original, settingsDraftOf(editingBoard.value))')
    })

    it('loads the board\'s value, sends an edit, and sends null for a blank', () => {
        const board = { cycleCap: 3, humanQueueAgeMinutes: 60 }
        const form = { ...board }
        expect(settingsDraftOf(form).humanQueueAgeMinutes).toBe(60)
        expect(settingsPatch(board, settingsDraftOf(form))).toBeNull()
        expect(settingsPatch(board, settingsDraftOf({ ...form, humanQueueAgeMinutes: 30 }))).toEqual({ humanQueueAgeMinutes: 30 })
        expect(settingsPatch(board, settingsDraftOf({ ...form, humanQueueAgeMinutes: null }))).toEqual({ humanQueueAgeMinutes: null })
        expect(settingsPatch(board, settingsDraftOf({ ...form, humanQueueAgeMinutes: undefined }))).toEqual({ humanQueueAgeMinutes: null })
    })

    it('reads every setting key off the form, the budget in dollars', () => {
        const draft = settingsDraftOf({ budgetDollars: 1.5, softAlertPercent: 70, cycleCap: 4, noProgressRepeatsToStop: 2,
            blockingPriority: 1, completionPriority: 2, humanQueueAgeMinutes: 45, eventRetentionDays: 0,
            coordinatorStopRelease: false })
        expect(Object.keys(draft).sort()).toEqual([...BOARD_SETTING_KEYS].sort())
        expect(draft).toEqual({ budgetMicros: 1_500_000, softAlertPercent: 70, cycleCap: 4, noProgressRepeatsToStop: 2,
            blockingPriority: 1, completionPriority: 2, humanQueueAgeMinutes: 45, eventRetentionDays: 0,
            coordinatorStopRelease: false })
        expect(Object.values(settingsDraftOf({})).every(v => v === null)).toBe(true)
    })
})

// The naming fields (task fceb1e57): the prefix and the documents block are in the form, saved
// through the utils the spec above pins, and a refusal about either is shown beside it.
describe('the board form: task-key prefix and documents', () => {
    it('binds the four fields', () => {
        expect(template).toContain('v-model:value="editingBoard.taskPrefix"')
        expect(template).toContain('v-model:value="editingBoard.documentsDraft.prefix"')
        expect(template).toContain('v-model:checked="editingBoard.documentsDraft.shared"')
        expect(template).toContain('v-model:checked="editingBoard.documentsDraft.rootSet"')
        expect(template).toContain('v-model:value="editingBoard.documentsDraft.root"')
        expect(template).toContain(':placeholder="editingBoardIsNew ? taskPrefixPlaceholder(editingBoard.name) : \'unchanged\'"')
    })

    it('saves both through the board input, create and update alike', () => {
        expect(source).toContain('const taskPrefix = taskPrefixPatch(original, editingBoard.value.taskPrefix)')
        expect(source).toContain('if (taskPrefix !== undefined) input.taskPrefix = taskPrefix')
        expect(source).toContain('const documents = documentsPatch(original, editingBoard.value.documentsDraft)')
        expect(source).toContain('if (documents !== undefined) input.documents = documents')
        const save = source.slice(source.indexOf('async function saveBoard'))
        expect(save.indexOf('input.documents = documents')).toBeLessThan(save.indexOf("store.dispatch('createAgentBoard'"))
        expect(source).toContain('taskPrefix: b.taskPrefix ?? \'\', heldTaskPrefix: b.taskPrefix ?? \'\', documentsDraft: documentsDraftOf(b)')
    })

    it('shows a refusal beside its field, verbatim', () => {
        expect(source).toContain('const field = boardFieldOfError(message)')
        expect(source).toContain('if (field) boardFieldErrors.value = { [field]: message }')
        const prefixError = template.slice(template.indexOf('data-testid="board-task-prefix-error"'))
        expect(prefixError.slice(0, prefixError.indexOf('</n-text>'))).toContain('boardFieldErrors.taskPrefix')
        const docsError = template.slice(template.indexOf('data-testid="board-documents-error"'))
        expect(docsError.slice(0, docsError.indexOf('</n-text>'))).toContain('{{ boardFieldErrors.documents }}')
    })
})

// The New task form carries a description and refuses a title the server would (T-3 of
// tests/fceb1e57/run-1.md).
describe('the board page: New task', () => {
    it('has a description beside the title, and sends both through taskRegisterInput', () => {
        const modal = template.slice(template.indexOf('title="New task"'))
        const form = modal.slice(0, modal.indexOf('</n-modal>'))
        expect(form).toContain('v-model:value="registering.title"')
        expect(form).toContain('v-model:value="registering.description" type="textarea"')
        expect(form).toContain('data-testid="new-task-title-error"')
        expect(source).toContain('input: { ...taskRegisterInput(registering.value), ...registerGroupFields(registering.value) },')
        expect(source).toContain("registering = { title: '', description: '', externalRef: '', sourceUrl: '', group: NO_GROUP, tagsText: '', level: null }")
    })

    it('cannot register a title or description the server refuses', () => {
        const can = source.slice(source.indexOf('const canRegister'), source.indexOf('async function registerTask'))
        expect(can).toContain('!taskTitleProblem(registering.value.title)')
        expect(can).toContain('!taskDescriptionProblem(registering.value.description)')
    })
})

// Both lists in one warning, the coverage read for the selected board only (task 5c70990d).
describe('the board warning: capabilities and coverage', () => {
    it('shows the capability line, then each coverage line, in one alert', () => {
        const alert = template.slice(template.indexOf('data-testid="board-warning"'))
        const body = alert.slice(0, alert.indexOf('</n-alert>'))
        expect(template).toContain('v-if="boardWarningShown(currentBoard.missingCapabilities, missingCoverage)"')
        expect(body).toContain('currentBoard.missingCapabilities.join')
        expect(body).toContain('v-for="line in coverageLines(missingCoverage)"')
        expect(body.indexOf('missingCapabilities.join')).toBeLessThan(body.indexOf('coverageLines(missingCoverage)'))
    })

    it('reads the coverage for the selected board, not with the board list', () => {
        expect(source).toContain("await store.dispatch('fetchAgentBoardCoverage', selectedBoard.value)")
        expect(source).toContain('    await loadCoverage()\n')
    })
})

// Task level on the board (RD2-1): the form's default level, the header chip, lanes and the URL.
describe('the board: task level', () => {
    it('the form has the default level beside the other settings, sent when changed, with its refusal beside it', () => {
        expect(template).toContain('v-model:value="editingBoard.defaultTaskLevel"')
        expect(template).toContain('data-testid="board-default-level-error"')
        expect(source).toContain('const defaultLevel = defaultLevelPatch(original, editingBoard.value.defaultTaskLevel)')
        expect(source).toContain('if (defaultLevel.changed) input.defaultTaskLevel = defaultLevel.value')
        const save = source.slice(source.indexOf('async function saveBoard'))
        expect(save.indexOf('input.defaultTaskLevel = defaultLevel.value')).toBeLessThan(save.indexOf("store.dispatch('createAgentBoard'"))
    })

    it('the header names the default level', () => {
        expect(template).toContain('data-testid="default-level-chip">default level {{ currentBoard.defaultTaskLevel }}')
    })

    it('the kanban groups by lane and filters by level, both kept in the URL', () => {
        expect(template).toContain('<div v-for="lane in kanbanLanes" :key="lane.key" class="lane" :data-lane="lane.key">')
        expect(template).toContain(`byStatus('PENDING_INTAKE', lane.tasks)`)
        expect(template).toContain('atRole(r.name, lane.tasks)')
        expect(source).toContain('const groupBy = ref<string>(groupByFromQuery(route.query))')
        expect(source).toContain('const levelFilter = ref<number | null>(levelFromQuery(route.query))')
        expect(source).toContain("withLevelQuery({ ...(route.query as Record<string, string>), tab: 'boards' },")
    })
})

// The target picker (task RD2-4): the choices, the patch and the chip are pinned in
// utils/agentBoardTarget.spec.ts; here, that the form and the header use them.
describe('the board form: target component', () => {
    const field = template.indexOf('data-testid="board-target"')

    it('sits between the description and the sources, bound to the board\'s target', () => {
        expect(field).toBeGreaterThan(-1)
        const tag = template.lastIndexOf('<n-select', field)
        expect(template.slice(tag, field)).toContain('v-model:value="editingBoard.target" filterable :options="targetOptions"')
        expect(template.indexOf('v-model:value="editingBoard.description"')).toBeLessThan(field)
        expect(template.indexOf('v-model:value="editingBoard.sources"')).toBeGreaterThan(field)
        expect(template.indexOf('v-model:value="editingBoard.perspectives"')).toBeGreaterThan(field)
    })

    it('keeps Save disabled on a new board without a target, and says why', () => {
        const save = template.indexOf('data-testid="board-save"')
        expect(template.slice(save, template.indexOf('</n-button>', save)))
            .toContain(':disabled="targetMissing(editingBoardIsNew, editingBoard.target)"')
        expect(template).toContain('v-else-if="targetMissing(editingBoardIsNew, editingBoard.target)"')
        expect(template).toContain('Target component: {{ TARGET_HINT }}')
        expect(source).toContain("taskPrefix: '', documentsDraft: documentsDraftOf(null), target: null }")
    })

    it('sends the target on create and on a changed update, before either mutation', () => {
        const save = source.slice(source.indexOf('async function saveBoard'))
        expect(save).toContain('const target = targetPatch(original, editingBoard.value.target)')
        expect(save).toContain('if (target !== undefined) input.target = target')
        expect(save.indexOf('input.target = target')).toBeLessThan(save.indexOf("store.dispatch('createAgentBoard'"))
        expect(save.indexOf('input.target = target')).toBeLessThan(save.indexOf("store.dispatch('updateAgentBoard'"))
    })

    it('offers the fetched software components, keeping the current target on Edit', () => {
        expect(source).toContain("targetComponents.value = await store.dispatch('fetchComponents', props.orgUuid) ?? []")
        expect(source).toContain(': targetOf(boards.value.find(x => x.uuid === editingBoard.value?.uuid), targetComponents.value)))')
        expect(source).toContain('void loadTargetComponents()')
    })

    it('shows a refusal about the target beside the field', () => {
        expect(template).toContain(':status="boardFieldErrors.target ? \'error\' : undefined"')
        const err = template.indexOf('data-testid="board-target-error"')
        expect(template.slice(err, template.indexOf('</n-text>', err))).toContain('{{ boardFieldErrors.target }}')
    })

    it('shows the target as a chip in the header, linking to the component', () => {
        const chip = template.indexOf('data-testid="target-chip"')
        expect(chip).toBeGreaterThan(-1)
        expect(template.slice(chip, template.indexOf('</n-tag>', chip)))
            .toContain('<RouterLink :to="boardTargetChip.to">{{ boardTargetChip.label }}</RouterLink>')
        expect(source).toContain('const boardTargetChip = computed(() => targetChip(currentBoard.value, props.orgUuid, targetComponents.value))')
        // T-1: the list is read quietly with the panel too, so the chip can name the target on a
        // server that does not resolve targetDetails.
        expect(source).toContain('onMounted(() => loadTargetComponents(true))')
        expect(source).toContain('if (!quiet) notification.error(')
    })
})

// Task groups and tags on the board page (RD2-31): the Groups tab over agentBoardGroupSet, the group
// and tag filters in the URL shared by the kanban and the table, the card's group colour, chip and
// gated tag, and the New task form's group, tags and level.
describe('the board page: groups and tags', () => {
    it('has a Groups tab that saves through agentBoardGroupSet and reloads the board', () => {
        expect(template).toContain('<n-tab-pane name="groups" tab="Groups">')
        expect(template).toContain('<AgentBoardGroupsPanel :board="currentBoard" :can-configure="canConfigure(currentBoard)" :save-group="saveGroup"/>')
        const save = source.slice(source.indexOf('async function saveGroup'), source.indexOf('async function registerTask'))
        expect(save).toContain("store.dispatch('agentBoardGroupSet', { boardUuid: currentBoard.value.uuid, group })")
        expect(save).toContain('await refreshBoards()')
        expect(source).toContain("const BOARD_VIEWS = ['kanban', 'pert', 'timeline', 'table', 'groups']")
    })

    it('filters the kanban and the table by group and tag, kept in the URL', () => {
        expect(template).toContain('data-testid="group-filter"')
        expect(template).toContain('data-testid="tag-filter"')
        expect(source).toContain('&& passesGroupAndTag(t, groupFilter.value, tagFilter.value)),')
        expect(source).toContain('groupBy.value, levelFilter.value), groupFilter.value, tagFilter.value)')
        expect(source).toContain('const groupFilter = ref<string | null>(groupFromQuery(route.query))')
        expect(source).toContain('const tagFilter = ref<string | null>(tagFromQuery(route.query))')
        expect(template).toContain(':group-filter="groupFilter" :tag-filter="tagFilter"')
        expect(template).toContain('@update:group-filter="setGroupFilter" @update:tag-filter="setTagFilter"')
    })

    it('colours a card by its group, names the group and says what a gated card waits on', () => {
        const card = source.slice(source.indexOf('const TaskCard = defineComponent'))
        expect(card).toContain('border-left: 3px solid ${groupColour(p.t.group?.key)}')
        expect(card).toContain("'data-testid': 'card-group'")
        expect(card).toContain("'data-testid': 'card-waiting'")
        expect(card).toContain('{ default: () => waitingOnLabel(p.t) }')
        expect(template).toContain("groupBy === 'group' && groupColour(lane.key === 'none' ? null : lane.key)")
    })

    it('registers a task into an open group with tags and a level', () => {
        const modal = template.slice(template.indexOf('title="New task"'))
        const form = modal.slice(0, modal.indexOf('</n-modal>'))
        expect(form).toContain(':options="groupOptions(currentBoard, { openOnly: true, none: true })"')
        expect(form).toContain('v-model:value="registering.tagsText" data-testid="new-task-tags"')
        expect(form).toContain('data-testid="new-task-level"')
        const can = source.slice(source.indexOf('const canRegister'), source.indexOf('async function registerTask'))
        expect(can).toContain('!tagsProblem(parseTags(registering.value.tagsText))')
    })

    it('moves and tags a task from the drawer', () => {
        expect(template).toContain('@set-group="setGroup" @set-tags="setTags"')
    })
})

// Access refusals that say so (RD2-9): an empty list is "none yet" only for an org admin, and a board
// link to a board the list does not hold says the person cannot open it instead of showing another.
describe('the boards tab for a person who cannot read a board', () => {
    it('words the empty list by whether the person reads every board', () => {
        expect(template).toContain('<div v-if="!boards.length" class="empty" data-testid="no-boards">{{ noBoardsText(isAdmin) }}</div>')
        expect(source).toContain('const isAdmin = computed<boolean>(() => isOrgAdmin(store.getters.myuser?.permissions?.permissions, props.orgUuid))')
    })

    it('says a linked board is not readable, and does not open another in its place', () => {
        expect(template).toContain('{{ hiddenBoardText(hiddenBoard) }}')
        const refresh = source.slice(source.indexOf('async function refreshBoards'), source.indexOf('async function refreshBoardContent'))
        expect(refresh).toContain('hiddenBoard.value = fromUrl && !known ? fromUrl : null')
        expect(refresh).toContain('selectedBoard.value = known ? (fromUrl as string) : hiddenBoard.value ? null : (boards.value[0]?.uuid ?? null)')
        expect(source).toContain('if (selectedBoard.value) hiddenBoard.value = null')
    })
})

// Controls shown to people who cannot act (RD2-6): the review banner asks only who can approve and
// informs a reader; View as spec needs configuration read; a refused spec read is said in the modal.
describe('the board page for a reader', () => {
    it('asks a person who can approve, and tells a reader the board waits on a person', () => {
        expect(template).toContain('<n-alert v-if="awaitingHumanReview.length && canOperate(currentBoard)" type="error" class="lockbanner"')
        const asked = template.slice(template.indexOf('data-testid="review-banner"'))
        expect(asked.slice(0, asked.indexOf('</n-alert>'))).toContain('awaiting your review:')
        const told = template.slice(template.indexOf('data-testid="review-banner-info"'))
        const info = told.slice(0, told.indexOf('</n-alert>'))
        expect(info).toContain('awaiting human review.')
        expect(info).not.toContain('your review')
        expect(info).not.toContain('<n-button')
    })

    it('offers View as spec only with configuration read, and shows a refusal in the modal', () => {
        expect(template).toContain('@click="openSpec" v-if="currentBoard && canConfigureRead(currentBoard)"')
        expect(template).toContain('<n-alert v-if="specError" type="warning" :bordered="false" data-testid="spec-error">{{ specError }}</n-alert>')
        expect(template).toContain('<pre v-else class="specBlock">{{ specText }}</pre>')
        const open = source.slice(source.indexOf('async function openSpec'), source.indexOf('async function copySpec'))
        expect(open).toContain('specError.value = specRefusal(e)')
        expect(open).not.toContain('showSpec.value = false')
    })
})

// The task key wherever a task is named (RD2-22): the card, the review banner and the board picker read
// through agentTaskKeys, whose own spec covers the labels.
describe('the board page: task keys', () => {
    const card = source.slice(source.indexOf('const TaskCard = defineComponent'), source.indexOf('const TaskCard = defineComponent') + 2500)

    it('leads the kanban card with the key, before the level, the group and the title', () => {
        const key = card.indexOf("'data-testid': 'card-key'")
        expect(key).toBeGreaterThan(-1)
        expect(card.indexOf('levelLabel(p.t, currentBoard.value)')).toBeGreaterThan(key)
        expect(card.indexOf('p.t.title,')).toBeGreaterThan(card.indexOf("'data-testid': 'card-group'"))
    })

    it('names each task awaiting review key first in the banner', () => {
        expect(template).toContain('{{ reviewBannerLabel(t) }}')
        expect(template).not.toContain("t.externalRef ? '#' + t.externalRef.split('#').pop() : t.title")
    })

    it('offers each board as prefix · name with a locked tag', () => {
        expect(template).toMatch(/:options="boardOptions"\s+:render-label="renderBoardOption"/)
        expect(source).toContain('const boardOptions = computed(() => boardPickerOptions(boards.value))')
    })
})

