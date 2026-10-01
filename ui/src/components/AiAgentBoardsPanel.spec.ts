import { describe, expect, it } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { parse as parseSfc } from '@vue/compiler-sfc'
import { compileString } from 'sass'
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
            coordinatorStopLift: false })
        expect(Object.keys(draft).sort()).toEqual([...BOARD_SETTING_KEYS].sort())
        expect(draft).toEqual({ budgetMicros: 1_500_000, softAlertPercent: 70, cycleCap: 4, noProgressRepeatsToStop: 2,
            blockingPriority: 1, completionPriority: 2, humanQueueAgeMinutes: 45, eventRetentionDays: 0,
            coordinatorStopLift: false })
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
        // The draft now comes from newTask, which the header and the empty board's hint share (RD2-13).
        expect(source).toContain("registering.value = { title: '', description: '', externalRef: '', sourceUrl: '', group: NO_GROUP, tagsText: '', workLevel: null }")
        expect(template).toContain('@click="newTask"')
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
        expect(template).toContain('<n-select v-model:value="editingBoard.defaultWorkLevel" :options="draftLevelOptions(editingBoard.ladderDraft)"')
        expect(template).toContain('data-testid="board-default-level-error"')
        expect(source).toContain('const defaultWorkLevel = defaultWorkLevelPatch(original, editingBoard.value.defaultWorkLevel)')
        expect(source).toContain('if (defaultWorkLevel.changed) input.defaultWorkLevel = defaultWorkLevel.value')
        const save = source.slice(source.indexOf('async function saveBoard'))
        expect(save.indexOf('input.defaultWorkLevel = defaultWorkLevel.value')).toBeLessThan(save.indexOf("store.dispatch('createAgentBoard'"))
    })

    it('the header names the default level, 0 unless set', () => {
        expect(template).toContain('data-testid="default-level-chip">default work level {{ levelLabel(currentBoard.defaultWorkLevel ?? 0, currentBoard) }}')
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

// The level ladder (task RD3-6): opt-in per board. The ladder's draft, patch and problems are pinned in
// utils/agentLadder.spec.ts and the editor in AgentBoardLadderEditor.spec.ts; here, that the form sends the
// ladder with the other settings and that everything level on the board page waits for a ladder.
describe('the board: level ladder', () => {
    const save = source.slice(source.indexOf('async function saveBoard'))
    const tag = (testid: string) => {
        const at = template.indexOf(`data-testid="${testid}"`)
        expect(at, testid).toBeGreaterThan(-1)
        return template.slice(template.lastIndexOf('<', at), at)
    }

    it('the form edits the ladder below the settings and loads it from the board', () => {
        expect(template).toContain('<AgentBoardLadderEditor v-model="editingBoard.ladderDraft" :error="boardFieldErrors.ladder"/>')
        expect(template.indexOf('<AgentBoardLadderEditor')).toBeGreaterThan(template.indexOf('data-testid="board-settings"'))
        expect(source).toContain('ladderDraft: ladderDraftOf(b.ladder)')
        expect(source).toContain('ladderDraft: ladderDraftOf(null)')
    })

    it('sends the ladder as settings.ladder when it changed, after its problems are checked', () => {
        expect(save).toContain('const ladder = ladderPatch(original?.ladder, editingBoard.value.ladderDraft)')
        expect(save).toContain('if (ladder !== undefined) input.settings = { ...(input.settings ?? {}), ladder }')
        expect(save.indexOf('ladderProblem(editingBoard.value.ladderDraft)')).toBeLessThan(save.indexOf('saving.value = true'))
        expect(save.indexOf('input.settings = { ...(input.settings ?? {}), ladder }')).toBeLessThan(save.indexOf("store.dispatch('createAgentBoard'"))
        // After the settings patch, so it is not overwritten by it.
        expect(save.indexOf('if (settings) input.settings = settings')).toBeLessThan(save.indexOf('const ladder = ladderPatch('))
    })

    it('offers the default level only with a ladder in the draft, its rungs by name', () => {
        expect(tag('board-default-level').length).toBeGreaterThan(0)
        const label = template.lastIndexOf('<label', template.indexOf('data-testid="board-default-level"'))
        expect(template.slice(label, template.indexOf('>', label))).toContain('v-if="draftLevelOptions(editingBoard.ladderDraft).length"')
    })

    it('shows the level filter, the new task\'s level and the default level chip only on a board with a ladder', () => {
        expect(tag('level-filter')).toContain('v-if="hasLadder(currentBoard)"')
        expect(tag('level-filter')).toContain(':options="levelOptions(currentBoard)"')
        expect(tag('new-task-level')).toContain('v-if="hasLadder(currentBoard)"')
        expect(tag('new-task-level')).toContain(':options="levelOptions(currentBoard)"')
        const chip = template.lastIndexOf('<n-tooltip', template.indexOf('data-testid="default-level-chip"'))
        expect(template.slice(chip, template.indexOf('>', chip))).toContain('v-if="hasLadder(currentBoard)"')
    })

    it('groups by level only on a board with a ladder, and ignores a level filter left in the URL without one', () => {
        expect(template).toContain('<n-radio-button v-for="o in groupByOptions(currentBoard)"')
        expect(template).toContain('<n-radio-group :value="shownGroupBy"')
        expect(source).toContain('const shownGroupBy = computed(() => groupingFor(groupBy.value, currentBoard.value))')
        expect(source).toContain('tasks.value.filter(t => passesLevel(t, currentBoard.value, levelFilter.value)')
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
        expect(template).toContain("shownGroupBy === 'group' && groupColour(lane.key === 'none' ? null : lane.key)")
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

// Controls shown to people who cannot act (RD2-6): the review banner asks only who can accept and
// informs a reader; View as spec needs configuration read; a refused spec read is said in the modal.
describe('the board page for a reader', () => {
    it('asks a person who can accept, and tells a reader the board waits on a person', () => {
        expect(template).toContain('<n-alert v-if="awaitingHumanReview.length && canOperate(currentBoard)" type="error" class="boardbanner"')
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

// RD2-14: Subscribe only for an org admin, opening the prefilled subscription form; everyone else a hint in its
// place, from subscribeOffer (agentBoardAccess.spec covers who gets which).
describe('the board header: Subscribe', () => {
    it('shows the button only when the offer is Subscribe, and the hint otherwise', () => {
        const button = template.indexOf('data-testid="subscribe"')
        expect(button).toBeGreaterThan(-1)
        expect(template.slice(template.lastIndexOf('<n-button', button), button))
            .toContain("v-if=\"currentBoard && subscribeOffer(currentBoard, isAdmin).kind === 'subscribe'\"")
        const hint = template.indexOf('data-testid="subscribe-hint"')
        expect(template.slice(template.lastIndexOf('<span', hint), hint)).toContain('v-else-if="currentBoard"')
        expect(template.slice(hint, template.indexOf('</span>', hint))).toContain('subscribeOffer(currentBoard, isAdmin)')
    })

    it('opens the subscription form prefilled for the board, as before', () => {
        expect(source).toContain("query: { tab: 'integrations', integrationsTab: 'subscriptions', newBoardSub: currentBoard.value.uuid }")
        expect(source).toContain('const isAdmin = computed<boolean>(() => isOrgAdmin(store.getters.myuser?.permissions?.permissions, props.orgUuid))')
    })
})

// The task key wherever a task is named (RD2-22): the card, the review banner and the board picker read
// through agentTaskKeys, whose own spec covers the labels.
describe('the board page: task keys', () => {
    const card = source.slice(source.indexOf('const TaskCard = defineComponent'), source.indexOf('const TaskCard = defineComponent') + 2500)

    it('leads the kanban card with the key, before the level, the group and the title', () => {
        const key = card.indexOf("'data-testid': 'card-key'")
        expect(key).toBeGreaterThan(-1)
        expect(card.indexOf('taskLevelLabel(p.t, currentBoard.value)')).toBeGreaterThan(key)
        expect(card.indexOf('p.t.title,')).toBeGreaterThan(card.indexOf("'data-testid': 'card-group'"))
    })

    it('names each task awaiting review key first in the banner', () => {
        expect(template).toContain('{{ reviewBannerLabel(t) }}')
        expect(template).not.toContain("t.externalRef ? '#' + t.externalRef.split('#').pop() : t.title")
    })

    it('offers each board as prefix · name with a paused tag', () => {
        expect(template).toMatch(/:options="boardOptions"\s+:render-label="renderBoardOption"/)
        expect(source).toContain('const boardOptions = computed(() => boardPickerOptions(boards.value))')
    })
})

// RD2-12 (sweep UI-17, UI-18, UI-19, UI-20): the board pages at 1280 -- short placeholders, the board form's
// settings label-above in two columns, table headers on one line, revision rows' actions on one line.
describe('board pages at 1280', () => {
    const read = (f: string) => readFileSync(fileURLToPath(new URL(f, import.meta.url)), 'utf8')

    it('lays the board form\'s settings out label above, two columns, with short placeholders', () => {
        const grid = template.slice(template.indexOf('<div class="form-grid" data-testid="board-settings">'))
        const block = grid.slice(0, grid.indexOf('\n                </div>'))
        // Eight plain fields, the staleness thresholds (one v-for) and the default level (with a ladder, task RD3-6).
        expect(block.match(/<label[^>]*class="fcell"><span class="flabel">/g)?.length).toBe(10, 'every setting labelled above')
        expect(block).not.toContain('<template #prefix>')
        expect(block).toContain('placeholder="none" data-testid="board-budget"')
        expect(block).toContain('placeholder="off"')
        expect(template).toContain('placeholder="PRs registered here (default)" size="small" style="min-width: 280px"')
        // 220 px: "merge commit (default)" is 168 px at 14 px, and the select's arrow and padding take 40 (tester run 1 T-5).
        expect(template).toContain('placeholder="merge commit (default)" size="small" style="min-width: 220px"')
    })

    // Tester run 1 T-2: the rules sat under .boardsPanel, and naive teleports the modal to the body, out of it.
    it('styles the settings grid through the board modal\'s own class, which the teleported card carries', () => {
        const modal = template.slice(template.indexOf('<n-modal :show="editingBoard !== null"'))
        expect(modal.slice(0, modal.indexOf('>'))).toContain('class="boardForm"')
        expect(modal.indexOf('<div class="form-grid" data-testid="board-settings">')).toBeLessThan(modal.indexOf('</n-modal>'))
        const css = parseSfc(source).descriptor.styles.filter(st => !st.scoped).map(st => compileString(st.content).css).join('\n')
        const selectorsOf = (decl: string) => [...css.matchAll(/([^{}]+)\{([^}]*)\}/g)]
            .filter(m => m[2].includes(decl)).map(m => m[1].trim())
        expect(selectorsOf('grid-template-columns: repeat(2, minmax(0, 1fr))')).toEqual(['.boardForm .form-grid'])
        expect(selectorsOf('flex-direction: column')).toContain('.boardForm .form-grid .fcell')
        expect(css).not.toMatch(/\.boardsPanel[^{]*\.form-grid/)
    })

    it('gives the task page\'s selects and budget short placeholders', () => {
        expect(read('./task/TaskReviewItems.vue')).toContain('placeholder="severity"')
        expect(read('./task/TaskHeader.vue')).toContain('placeholder="severity"')
        // 170 px: the "budget $" prefix and the steppers leave the input 52 px; "none" needs 35 (tester run 1 T-3).
        expect(read('./task/TaskActions.vue')).toContain('placeholder="none" style="width: 170px" data-testid="task-budget"')
    })

    it('keeps the table\'s headers on one line, Order wide enough', () => {
        const table = read('./AiAgentTaskTableView.vue')
        expect(table).toContain('class="ttable__table"')
        expect(table).toContain('.ttable__table :deep(.n-data-table-th) { white-space: nowrap; }')
        expect(table).toContain("{ title: 'Order', key: 'orderIndex', width: 80, minWidth: 80,")
    })

    it('keeps a revision row\'s actions on one line beside its facts', () => {
        const rev = read('./AiAgentRevisionHistory.vue')
        expect(rev).toContain('<span class="revhist__actions">')
        expect(rev).toContain('.revhist__actions { display: inline-flex; flex-shrink: 0; white-space: nowrap;')
        expect(rev).toMatch(/\.revhist__line \{\s*display: flex;\s*flex-wrap: nowrap;/)
        // Tester run 1 T-4: at 1280 the date wrapped before the facts gave way.
        expect(rev).toContain('.revhist__at { flex-shrink: 0; white-space: nowrap; }')
    })
})

// RD2-13 (sweep UI-21, UI-21b, UI-36, UI-42, UI-38): the kanban's cards and columns; the rules are agentKanban's
// and agentTaskLabels', whose specs cover them.
describe('the kanban: cards and columns', () => {
    const card = source.slice(source.indexOf('const TaskCard = defineComponent'), source.indexOf('const TaskCard = defineComponent') + 6000)

    it('tells an empty board how tasks arrive, with New task for who may add one', () => {
        const hint = template.indexOf('data-testid="empty-board"')
        expect(template.slice(template.lastIndexOf('<div', hint), hint)).toContain('v-if="!tasks.length"')
        expect(template.slice(hint, template.indexOf('</div>', hint))).toContain('{{ EMPTY_BOARD_HINT }}')
        expect(template.slice(hint, template.indexOf('</div>', hint))).toContain('v-if="currentBoard && canOperate(currentBoard)"')
    })

    it('folds an empty column to its head with its count, and opens it on a click', () => {
        const awaiting = template.slice(template.indexOf('data-col="awaiting"') - 200, template.indexOf('data-col="awaiting"') + 700)
        expect(awaiting).toContain(":class=\"{ 'col--folded': folded('awaiting', byStatus('AWAITING_COORDINATOR', lane.tasks)) }\"")
        expect(awaiting).toContain('@click="openColumn(\'awaiting\')"')
        expect(awaiting).toContain('data-testid="col-count"')
        expect(awaiting).toContain('<template v-if="!folded(\'awaiting\', byStatus(\'AWAITING_COORDINATOR\', lane.tasks))">')
        expect(template.match(/data-testid="col-count"/g)?.length).toBe(6, 'every column head counts its cards')
        expect(template).toContain('data-testid="more-lanes"')
    })

    it('wraps and ellipsizes the chips, names the blocker once, and says when a PR moved', () => {
        expect(source).toMatch(/\.tcard__meta \{ display: flex; flex-wrap: wrap;/)
        expect(source).toMatch(/\.tcard__meta \.n-tag \.n-tag__content \{ overflow: hidden; text-overflow: ellipsis;/)
        expect(card).toContain('waitsOnLabel(p.t, tasks.value)')
        expect(card).not.toContain("'blocked by '")
        expect(card).not.toContain("h('span', { class: 'deplabel' }, 'after')")
        expect(card).toContain("{ default: () => 'PR moved · re-test' }")
        expect(card).toContain('prChips(p.t).some((c: any) => c.moved)')
    })

    it('reads the PR heads the moved chip needs with the board\'s tasks', () => {
        const store = readFileSync(fileURLToPath(new URL('../store.ts', import.meta.url)), 'utf8')
        const q = store.slice(store.indexOf('query agentTasksOfBoard('), store.indexOf('query agentTasksOfBoard(') + 400)
        expect(q).toContain('testedHeads { pr head }')
        expect(q).toContain('pullRequests { url head }')
    })
})

// Tester run 1 T-2: the hint sat under the strip, below the fold at 900 high; it now sits above it.
describe('the kanban: more-lanes hint', () => {
    it('shows above each lane\'s strip, and the strip is measured through the tested helper', () => {
        const hint = template.indexOf('data-testid="more-lanes"')
        const strip = template.indexOf('<div class="board" :ref="(el: any) => boardEl(lane.key, el)"')
        expect(hint).toBeGreaterThan(-1)
        expect(hint).toBeLessThan(strip)
        expect(template.slice(strip).indexOf('data-testid="more-lanes"')).toBe(-1, 'not under the strip too')
        expect(source).toContain('const strips = stripMeasure(fn => { nextTick(fn) }, pastEdge)')
    })
})

