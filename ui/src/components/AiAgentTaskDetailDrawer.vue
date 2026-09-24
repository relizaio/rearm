<template>
    <n-drawer :show="task !== null" :width="560" placement="right"
              @update:show="(v: boolean) => { if (!v) emit('close') }">
        <n-drawer-content v-if="task" closable>
            <template #header>
                <div class="dhead">
                    <div class="dhead__title">{{ task.title }}</div>
                    <div class="dhead__sub">
                        <a v-if="task.sourceUrl" :href="task.sourceUrl" target="_blank" rel="noopener">
                            {{ (task.externalRef ?? 'draft').replace(/^github:/, '') }}
                        </a>
                        <span v-else>{{ (task.externalRef ?? 'draft — no tracker ref yet').replace(/^github:/, '') }}</span>
                        <n-tag size="small" :bordered="false" :type="statusTone(task.status)">
                            {{ task.status.replace(/_/g, ' ') }}
                        </n-tag>
                        <n-tag v-if="task.role" size="small" :bordered="false">{{ task.role }} · #{{ task.orderIndex }}</n-tag>
                    </div>
                </div>
            </template>

            <n-space vertical :size="16">
                <n-alert v-if="task.hold" type="error"
                         :title="task.hold.kind === 'HUMAN_GATE' ? 'Awaiting human review' : `On hold (${(task.hold.level ?? '').toLowerCase()})`">
                    {{ task.hold.reason }}
                    <div class="holdmeta">held by {{ actorLabel(task.hold.heldBy) }} · {{ ts(task.hold.heldAt) }}</div>
                    <template v-if="task.hold.kind === 'HUMAN_GATE'">
                        <n-input v-model:value="reviewNote" size="small" placeholder="Review note (optional)"
                                 style="margin-top: 8px"/>
                        <!-- A rejection with a finding attached routes like a reviewer's: to whoever
                             produces what it is about. Without one it goes to the coordinator. -->
                        <n-space :size="6" style="margin-top: 8px" align="center">
                            <n-input v-model:value="gateFindingTitle" size="small"
                                     placeholder="Finding to reject with (optional)" style="width: 230px"/>
                            <n-select v-model:value="gateFindingPriority" :options="priorityOptions" size="small"
                                      style="width: 72px"/>
                            <n-select v-model:value="gateAbout" :options="aboutOptions" size="small" clearable
                                      placeholder="about" style="width: 150px"/>
                        </n-space>
                        <n-space style="margin-top: 8px">
                            <n-button size="small" type="primary"
                                      @click="emit('human-review', { task, approve: true, note: reviewNote })">
                                Approve {{ task.hold.gateRole }} pass
                            </n-button>
                            <n-button size="small" type="error" ghost @click="rejectAtGate">
                                Reject{{ gateFindingTitle.trim() ? ' with finding' : '' }}
                            </n-button>
                        </n-space>
                    </template>
                    <template v-else-if="task.hold.kind === 'QUESTION'">
                        <div class="holdmeta">
                            A question on this task has nobody to answer it. Answer it below — the
                            board routes your answer back to whoever asked.
                        </div>
                    </template>
                    <template v-else-if="task.hold.level === 'OPERATOR'">
                        <n-input v-model:value="releaseNote" size="small"
                                 placeholder="Note on release (optional)" style="margin-top: 8px"/>
                        <n-space style="margin-top: 8px">
                            <n-button size="small"
                                      @click="emit('operator-release', { task, note: releaseNote })">
                                Operator release
                            </n-button>
                        </n-space>
                    </template>
                </n-alert>

                <n-alert v-if="humanStageRole" type="info" :title="`Human stage: ${humanStageRole.name}`">
                    <div v-if="humanStageRole.prompt" class="holdmeta">{{ humanStageRole.prompt }}</div>
                    <n-input v-model:value="reviewNote" size="small" placeholder="Sign-off note (optional)"
                             style="margin-top: 8px"/>
                    <n-space style="margin-top: 8px">
                        <n-button size="small" type="primary"
                                  @click="emit('human-signoff', { task, outcome: 'PASSED', note: reviewNote })">
                            Sign off PASSED
                        </n-button>
                        <n-button size="small" type="error" ghost
                                  @click="emit('human-signoff', { task, outcome: 'REJECTED', note: reviewNote })">
                            REJECTED
                        </n-button>
                    </n-space>
                </n-alert>

                <div v-if="!terminal" class="dsec">
                    <div class="dsec__h">Human review</div>
                    <div class="deprow">
                        <n-tag v-if="task.requireHumanReview" size="small" :bordered="false" type="warning">
                            next sign-off requires human review
                        </n-tag>
                        <n-button size="tiny" quaternary
                                  @click="emit('require-review', { task, value: !task.requireHumanReview })">
                            {{ task.requireHumanReview ? 'clear flag (operator)' : 'require human review of next sign-off' }}
                        </n-button>
                        <n-tag v-for="m in missingRequired" :key="m" size="small" :bordered="false" type="error">
                            required: {{ m }} ✗
                        </n-tag>
                    </div>
                </div>

                <div v-if="!terminal" class="dsec">
                    <div class="dsec__h">Task actions</div>
                    <div v-if="authorizable" class="deprow">
                        <n-select v-model:value="authorizeRole" :options="roleOptions" size="small"
                                  placeholder="role" style="width: 170px"/>
                        <n-button size="small" :disabled="!authorizeRole"
                                  @click="emit('authorize', { task, role: authorizeRole, orderIndex: orderDraft })">
                            Authorize
                        </n-button>
                    </div>
                    <div class="deprow">
                        <n-input-number v-model:value="orderDraft" size="small" :min="0" style="width: 130px">
                            <template #prefix><span class="deplab" style="min-width: 0">order</span></template>
                        </n-input-number>
                        <n-button size="small" :disabled="orderDraft == null || orderDraft === task.orderIndex"
                                  @click="emit('order', { task, orderIndex: orderDraft })">Set order</n-button>
                        <span v-if="task.orderSetBy" class="holdmeta" style="margin-top: 0">
                            set by {{ actorLabel(task.orderSetBy) }} · {{ ts(task.orderSetAt) }}
                        </span>
                    </div>
                    <div class="deprow">
                        <n-button size="small" type="primary" ghost :disabled="!completable"
                                  @click="showComplete = true">Complete…</n-button>
                        <n-input v-model:value="cancelNote" size="small" placeholder="Why cancel (optional)"
                                 style="width: 220px"/>
                        <n-popconfirm @positive-click="emit('cancel', { task, note: cancelNote })">
                            <template #trigger>
                                <n-button size="small" type="error" ghost>Cancel task</n-button>
                            </template>
                            Cancelling is final. An agent working it finds it gone at its next call.
                        </n-popconfirm>
                    </div>
                </div>

                <div v-if="task.dependsOn?.length || dependents.length" class="dsec">
                    <div class="dsec__h">Dependencies</div>
                    <div v-if="task.dependsOn?.length" class="deprow">
                        <span class="deplab">after</span>
                        <n-tag v-for="d in resolvedDeps" :key="d.uuid" size="small" :bordered="false"
                               :type="d.status === 'COMPLETED' ? 'success' : 'warning'"
                               class="depclick" @click="emit('open', d)">
                            {{ label(d) }} · {{ d.status === 'COMPLETED' ? 'done' : 'pending' }}
                        </n-tag>
                    </div>
                    <div v-if="dependents.length" class="deprow">
                        <span class="deplab">blocks</span>
                        <n-tag v-for="d in dependents" :key="d.uuid" size="small" :bordered="false"
                               class="depclick" @click="emit('open', d)">
                            {{ label(d) }}
                        </n-tag>
                    </div>
                </div>

                <div v-if="task.parentTask || task.childTasks?.length" class="dsec">
                    <div class="dsec__h">Lineage</div>
                    <div class="deprow" v-if="parentTask">
                        <span class="deplab">parent</span>
                        <n-tag size="small" :bordered="false" type="info" class="depclick"
                               @click="emit('open', parentTask)">{{ label(parentTask) }}</n-tag>
                    </div>
                    <div class="deprow" v-if="childTasksResolved.length">
                        <span class="deplab">subtasks</span>
                        <n-tag v-for="c in childTasksResolved" :key="c.uuid" size="small" :bordered="false"
                               :type="c.status === 'COMPLETED' ? 'success' : 'default'" class="depclick"
                               @click="emit('open', c)">{{ label(c) }}</n-tag>
                    </div>
                </div>

                <div v-if="task.assignment" class="dsec">
                    <div class="dsec__h">Current assignment</div>
                    <div class="hist">
                        <div class="hist__row hist__row--active">
                            <span class="hist__role">{{ task.assignment.role }}</span>
                            <span class="hist__agent">{{ agentName(task.assignment.agent) }}</span>
                            <span class="hist__time">since {{ ts(task.assignment.assignedAt) }}
                                ({{ dur(task.assignment.assignedAt, null) }})</span>
                            <code v-if="task.assignment.promptVersion" class="hist__pv"
                                  title="Served role-prompt version">{{ task.assignment.promptVersion }}</code>
                        </div>
                    </div>
                </div>

                <div class="dsec" v-if="(task.usage?.reports ?? 0) > 0">
                    <div class="dsec__h">Usage</div>
                    <agent-usage-summary :usage="task.usage" :show-by-model="false" />
                </div>

                <!-- The NEWEST round of each indexed type. Every round carries forward what the
                     previous one left open, so the newest is the current state; summing rounds would
                     count one finding several times. A decision is a new round, never an edit. -->
                <div class="dsec" v-for="r in findingRounds" :key="r.spec">
                    <div class="dsec__h">
                        {{ r.spec === 'TEST_REPORT' ? 'Test findings' : 'Review findings' }}
                        <template v-if="r.release.document?.round"> · round {{ r.release.document.round }}</template>
                        <n-tag v-if="documentVerdict(r.release)" size="tiny" :bordered="false"
                               :type="verdictType(documentVerdict(r.release))" style="margin-left: 6px">
                            {{ documentVerdict(r.release) }}
                        </n-tag>
                    </div>
                    <div v-for="f in r.findings" :key="f.id ?? ''" class="frow"
                         :class="{ 'frow--closed': f.status !== 'OPEN' }">
                        <code class="frow__id">{{ f.id }}</code>
                        <n-tag size="tiny" :bordered="false" :type="f.priority === 1 ? 'error' : 'warning'">
                            P{{ f.priority ?? '?' }}
                        </n-tag>
                        <n-tag v-if="f.status !== 'OPEN'" size="tiny" :bordered="false"
                               :type="statusType(f.status)">{{ f.status }}</n-tag>
                        <span class="frow__title">{{ f.title }}</span>
                        <code v-if="findingLocation(f)" class="frow__loc">{{ findingLocation(f) }}</code>
                        <span v-if="f.decidedBy" class="frow__dec" :title="f.resolution ?? ''">
                            {{ f.decidedBy.kind === 'USER' ? 'decided by' : 'agent decided' }}
                            {{ actorLabel(f.decidedBy) }}<template v-if="f.decidedAt"> · {{ ts(f.decidedAt) }}</template>
                        </span>
                        <n-button v-if="canDecide && f.status === 'OPEN'" size="tiny" quaternary
                                  @click="toggleDecide(r.spec, f)">decide</n-button>
                        <div v-if="deciding === r.spec + '/' + f.id" class="fedit">
                            <n-input v-model:value="decisionWords" size="small"
                                     placeholder="Why (needed to accept or dismiss)"/>
                            <n-space :size="6" style="margin-top: 6px" align="center">
                                <n-button size="tiny" type="warning" :disabled="!decisionWords.trim()"
                                          @click="decideOne(r.spec, { action: 'ACCEPT', findingId: f.id, resolution: decisionWords.trim() })">
                                    Accept the risk
                                </n-button>
                                <n-button size="tiny" :disabled="!decisionWords.trim()"
                                          @click="decideOne(r.spec, { action: 'DISMISS', findingId: f.id, resolution: decisionWords.trim() })">
                                    Dismiss
                                </n-button>
                                <n-select v-model:value="decisionPriority" :options="priorityOptions" size="tiny"
                                          style="width: 72px"/>
                                <n-button size="tiny" :disabled="decisionPriority == null || decisionPriority === f.priority"
                                          @click="decideOne(r.spec, { action: 'SET_PRIORITY', findingId: f.id, priority: decisionPriority })">
                                    Set priority
                                </n-button>
                            </n-space>
                        </div>
                    </div>
                </div>

                <div v-if="canDecide" class="dsec">
                    <div class="dsec__h">File a finding</div>
                    <n-space :size="6" align="center">
                        <n-select v-model:value="fileSpec" :options="fileSpecOptions" size="small" style="width: 130px"/>
                        <n-input v-model:value="fileTitle" size="small" placeholder="What is wrong" style="width: 200px"/>
                        <n-select v-model:value="filePriority" :options="priorityOptions" size="small" style="width: 72px"/>
                        <n-select v-if="!aboutOf(fileSpec)" v-model:value="fileAbout" :options="aboutOptions" size="small"
                                  clearable placeholder="about" style="width: 150px"/>
                        <n-button size="small" :disabled="!fileTitle.trim() || filePriority == null" @click="fileFinding">
                            File
                        </n-button>
                    </n-space>
                    <div class="holdmeta">
                        A blocking finding sends the task to the role that produces what it is about, or to
                        the coordinator when it names nothing.
                    </div>
                </div>

                <div class="dsec" v-if="openQuestionGroups.length && !answerable.length">
                    <div class="dsec__h">Open questions</div>
                    <div v-for="g in openQuestionGroups" :key="String(g.priority)" class="fgroup">
                        <div v-for="f in g.findings" :key="f.id ?? ''" class="frow">
                            <code class="frow__id">{{ f.id }}</code>
                            <span class="frow__title">{{ f.title }}</span>
                        </div>
                    </div>
                </div>

                <div class="dsec" v-if="taskDocuments.length">
                    <div class="dsec__h">Documents</div>
                    <div v-for="d in taskDocuments" :key="d.uuid ?? ''" class="drow">
                        <span class="drow__label">{{ documentLabel(d) }}</span>
                        <n-tag v-if="documentVerdict(d)" size="tiny" :bordered="false"
                               :type="verdictType(documentVerdict(d))">{{ documentVerdict(d) }}</n-tag>
                        <n-tag v-if="testCounts(d)" size="tiny" :bordered="false" type="info">
                            {{ testCounts(d)?.failed }} failed / {{ testCounts(d)?.passed }} passed
                        </n-tag>
                        <!-- A link only where we can build one. A guessed URL that 404s reads as
                             "the document is missing", so an unknown host shows the path instead. -->
                        <a v-if="documentFileUrl(d)" :href="documentFileUrl(d) ?? undefined" target="_blank"
                           rel="noopener" class="drow__path">{{ d.document?.path }}</a>
                        <code v-else class="drow__path drow__path--plain">{{ d.document?.path }}</code>
                        <code v-if="d.sourceCodeEntryDetails?.commit" class="drow__commit"
                              title="Commit this document is pinned to">{{ d.sourceCodeEntryDetails.commit.slice(0, 8) }}</code>
                    </div>
                </div>

                <div class="dsec">
                    <div class="dsec__h">History</div>
                    <div v-if="!history.length" class="empty">No hops recorded yet.</div>
                    <div class="hist">
                        <div v-for="(e, i) in history" :key="i" class="hist__row">
                            <template v-if="e.kind === 'signoff'">
                                <n-tag size="tiny" :bordered="false"
                                       :type="e.rec.outcome === 'PASSED' ? 'success' : 'error'">
                                    {{ e.rec.outcome }}
                                </n-tag>
                                <span class="hist__role">{{ e.rec.role }}</span>
                                <n-tag v-if="e.rec.reviewedBy" size="tiny" :bordered="false" type="info">human</n-tag>
                                <span class="hist__agent">{{ actorLabel(e.rec.reviewedBy) || agentName(e.rec.agent) }}</span>
                                <span class="hist__time">{{ ts(e.rec.signedOffAt) }}<template v-if="e.rec.assignedAt">
                                    · worked {{ dur(e.rec.assignedAt, e.rec.signedOffAt) }}</template></span>
                                <code v-if="e.rec.promptVersion" class="hist__pv"
                                      title="Served role-prompt version">{{ e.rec.promptVersion }}</code>
                                <span v-if="hopHasUsage(e.rec)" class="hist__usage"
                                      :title="hopTitle(e.rec)">{{ hopLabel(e.rec) }}</span>
                                <div v-if="hopOutputs(e.rec).length" class="hist__outputs">
                                    <span v-for="o in hopOutputs(e.rec)" :key="o.uuid ?? ''" class="hist__output">
                                        <a v-if="documentFileUrl(o)" :href="documentFileUrl(o) ?? undefined"
                                           target="_blank" rel="noopener">{{ documentLabel(o) }}</a>
                                        <span v-else>{{ documentLabel(o) }}</span>
                                    </span>
                                </div>
                                <div v-if="e.rec.note" class="hist__note">{{ e.rec.note }}</div>
                            </template>
                            <template v-else>
                                <n-tag size="tiny" :bordered="false" type="warning">RETURNED</n-tag>
                                <span class="hist__role">{{ e.rec.role }}</span>
                                <span class="hist__agent">{{ agentName(e.rec.agent) }}</span>
                                <span class="hist__time">{{ ts(e.rec.returnedAt) }} · {{ e.rec.reason }}</span>
                                <span v-if="hopHasUsage(e.rec)" class="hist__usage"
                                      :title="hopTitle(e.rec)">{{ hopLabel(e.rec) }}</span>
                                <div v-if="hopOutputs(e.rec).length" class="hist__outputs">
                                    <span v-for="o in hopOutputs(e.rec)" :key="o.uuid ?? ''" class="hist__output">
                                        <a v-if="documentFileUrl(o)" :href="documentFileUrl(o) ?? undefined"
                                           target="_blank" rel="noopener">{{ documentLabel(o) }}</a>
                                        <span v-else>{{ documentLabel(o) }}</span>
                                    </span>
                                </div>
                                <div v-if="e.rec.description" class="hist__note">{{ e.rec.description }}</div>
                            </template>
                        </div>
                    </div>
                </div>

                <div v-if="task.prUrls?.length" class="dsec">
                    <div class="dsec__h">Pull requests</div>
                    <div class="deprow">
                        <a v-for="pr in task.prUrls" :key="pr" :href="pr" target="_blank"
                           rel="noopener" class="prlink2">{{ pr.split('/').slice(-3).join('/') }}</a>
                    </div>
                </div>

                <div v-if="task.questionStack?.length" class="dsec">
                    <div class="dsec__h">Waiting on</div>
                    <div class="qstack">
                        <div v-for="(f, i) in task.questionStack" :key="i" class="qstack__row">
                            <span class="qstack__depth">{{ i + 1 }}</span>
                            <span>{{ roleName(f.askingRole) }} asked {{ roleName(f.answeringRole) || 'nobody yet' }}</span>
                            <a v-if="f.questionsRelease" :href="`/release/${f.questionsRelease}`" class="qstack__link">questions</a>
                            <span class="qstack__time">{{ ts(f.askedAt) }}</span>
                        </div>
                    </div>
                    <div v-if="!task.questionStack[task.questionStack.length - 1].answeringRole"
                         class="qstack__note">
                        The board found no role that produces what the newest question is about, so
                        it is with the coordinator to name one or escalate.
                    </div>

                    <!--
                        Answering is a round of the QUESTIONS index, not a note: that is what the
                        asking agent reads as a pinned input when the task comes back to it. A
                        note would be prose it cannot pin, and the loop would ask again.
                    -->
                    <div v-if="answerable.length" class="qans">
                        <div class="dsec__h" style="margin-top: 4px">Answer</div>
                        <div v-for="f in answerable" :key="f.id" class="qans__row">
                            <div class="qans__id">
                                <span class="qans__tag">{{ f.id }}</span>
                                <span class="qans__title">{{ f.title }}</span>
                            </div>
                            <n-input v-model:value="answers[f.id]" size="small" type="textarea"
                                     :autosize="{ minRows: 1, maxRows: 4 }"
                                     :placeholder="`Answer to ${f.id}`"/>
                            <n-checkbox v-model:checked="withdrawn[f.id]" size="small">
                                does not apply (say why above)
                            </n-checkbox>
                        </div>
                        <n-input v-model:value="answerAll" size="small" type="textarea"
                                 :autosize="{ minRows: 1, maxRows: 4 }"
                                 placeholder="Same answer to all of them"
                                 style="margin-top: 8px"/>
                        <n-space style="margin-top: 8px">
                            <n-button size="small" type="primary" :disabled="!canAnswer"
                                      @click="emit('answer', answerPayload)">
                                {{ task.hold ? 'Answer and release' : 'Answer' }}
                            </n-button>
                        </n-space>
                    </div>
                </div>

                <div v-if="task.statusHistory?.length" class="dsec">
                    <div class="dsec__h">Status history</div>
                    <div class="shist">
                        <div v-for="(c, i) in task.statusHistory" :key="i" class="shist__row">
                            <span class="shist__time">{{ ts(c.at) }}</span>
                            <span class="shist__arrow">{{ (c.from ?? '·').toLowerCase().replace(/_/g, ' ') }} → {{ c.to.toLowerCase().replace(/_/g, ' ') }}</span>
                            <code class="shist__trig">{{ c.trigger }}</code>
                            <span v-if="actorLabel(c.actor)" class="shist__by">by {{ actorLabel(c.actor) }}</span>
                            <span v-if="c.note" class="shist__note">“{{ c.note }}”</span>
                            <span v-if="i > 0" class="shist__dur">+{{ dur(task.statusHistory[i-1].at, c.at) || '0m' }}</span>
                        </div>
                    </div>
                </div>

                <div class="dsec">
                    <div class="dsec__h">Provenance</div>
                    <div class="prov">
                        <div>created {{ ts(task.createdDate) }}<template v-if="task.completedAt"> · completed {{ ts(task.completedAt) }}</template></div>
                        <div v-if="task.registeredBySession">registered by session <code>{{ shortId(task.registeredBySession) }}</code></div>
                        <div v-if="task.sessions?.length">worked by {{ task.sessions.length }} session{{ task.sessions.length > 1 ? 's' : '' }}:
                            <code v-for="s in task.sessions" :key="s" class="sesschip">{{ shortId(s) }}</code>
                        </div>
                    </div>
                </div>
            </n-space>

            <!-- A person's complete: findings that block completion are decided one by one, never
                 skipped; required roles that have not passed may be skipped, with a note. -->
            <n-modal :show="showComplete" preset="card" title="Complete task" style="max-width: 560px"
                     @update:show="(v: boolean) => { showComplete = v }">
                <n-space vertical :size="12">
                    <div v-if="blockers.length">
                        <div class="dsec__h">Findings that block completion</div>
                        <div v-for="b in blockers" :key="b.specification + b.finding.id" class="frow">
                            <code class="frow__id">{{ b.finding.id }}</code>
                            <n-tag size="tiny" :bordered="false" type="error">P{{ b.finding.priority ?? '?' }}</n-tag>
                            <span class="frow__title">{{ b.finding.title }}</span>
                            <div class="fedit">
                                <n-input v-model:value="blockerWords[b.finding.id ?? '']" size="small"
                                         placeholder="Why"/>
                                <n-space :size="6" style="margin-top: 6px">
                                    <n-button size="tiny" type="warning"
                                              :disabled="!(blockerWords[b.finding.id ?? ''] ?? '').trim()"
                                              @click="decideOne(b.specification, { action: 'ACCEPT', findingId: b.finding.id, resolution: blockerWords[b.finding.id ?? ''].trim() })">
                                        Accept the risk
                                    </n-button>
                                    <n-button size="tiny"
                                              :disabled="!(blockerWords[b.finding.id ?? ''] ?? '').trim()"
                                              @click="decideOne(b.specification, { action: 'DISMISS', findingId: b.finding.id, resolution: blockerWords[b.finding.id ?? ''].trim() })">
                                        Dismiss
                                    </n-button>
                                </n-space>
                            </div>
                        </div>
                    </div>
                    <div v-if="missingRequired.length">
                        <div class="dsec__h">Required roles without a pass</div>
                        <n-tag v-for="m in missingRequired" :key="m" size="small" :bordered="false" type="error"
                               style="margin-right: 6px">{{ m }}</n-tag>
                        <div class="holdmeta">
                            A role whose rejection you have decided over counts as passed. Otherwise, skip
                            it and say why.
                        </div>
                        <n-checkbox v-model:checked="skipRequired" style="margin-top: 6px">
                            complete without them
                        </n-checkbox>
                    </div>
                    <n-input v-model:value="completeNote" type="textarea" :autosize="{ minRows: 2, maxRows: 5 }"
                             :placeholder="skipRequired ? 'Why (required when skipping roles)' : 'Note (optional)'"/>
                    <n-space justify="end">
                        <n-button size="small" @click="showComplete = false">Back</n-button>
                        <n-button size="small" type="primary"
                                  :disabled="blockers.length > 0 || (skipRequired && !completeNote.trim())"
                                  @click="emit('complete', { task, note: completeNote, skipRequiredRoles: skipRequired })">
                            Complete
                        </n-button>
                    </n-space>
                </n-space>
            </n-modal>
        </n-drawer-content>
    </n-drawer>
</template>

<script lang="ts" setup>
import { computed, ref, watch } from 'vue'
import { NAlert, NButton, NCheckbox, NDrawer, NDrawerContent, NInput, NInputNumber, NModal, NPopconfirm, NSelect, NSpace, NTag } from 'naive-ui'
import AgentUsageSummary from './AgentUsageSummary.vue'
import { costLabel, formatTokens, totalTokens } from '@/utils/agentUsage'
import { actorLabel } from '@/utils/agentActors'
import {
    DECIDABLE_STATUSES,
    DocumentRelease,
    Finding,
    INDEXED_TYPES,
    completionBlockers,
    documentFileUrl,
    documentLabel,
    documentVerdict,
    findingLocation,
    groupByPriority,
    latestRound,
    outputsOfHop,
    sortFindings,
    statusType,
    testCounts,
    verdictType,
} from '@/utils/agentDocuments'

const props = defineProps<{
    task: any | null
    tasks: any[]
    agentNames: Record<string, string>
    roles?: any[]
    board?: any
    priorityLevels?: number
}>()
const emit = defineEmits<{
    (e: 'close'): void
    (e: 'open', task: any): void
    (e: 'human-review', p: { task: any, approve: boolean, note: string, findings?: any[],
        about?: { specification: string } | null }): void
    (e: 'human-signoff', p: { task: any, outcome: string, note: string }): void
    (e: 'operator-release', p: { task: any, note: string }): void
    (e: 'require-review', p: { task: any, value: boolean }): void
    (e: 'answer', p: { task: any, answers: { id: string, status: string, resolution: string }[],
        answerAll?: string }): void
    (e: 'authorize', p: { task: any, role: string, orderIndex?: number | null }): void
    (e: 'order', p: { task: any, orderIndex: number }): void
    (e: 'complete', p: { task: any, note: string, skipRequiredRoles: boolean }): void
    (e: 'cancel', p: { task: any, note: string }): void
    (e: 'decide', p: { task: any, specification: string, decisions: any[],
        about?: { specification: string } | null }): void
}>()

// ---------- operator actions ----------

const authorizeRole = ref<string | null>(null)
const orderDraft = ref<number | null>(null)
const cancelNote = ref('')
const showComplete = ref(false)
const completeNote = ref('')
const skipRequired = ref(false)
const blockerWords = ref<Record<string, string>>({})
const deciding = ref<string | null>(null)
const decisionWords = ref('')
const decisionPriority = ref<number | null>(null)
const fileSpec = ref('REVIEW_FINDINGS')
const fileTitle = ref('')
const filePriority = ref<number | null>(null)
const fileAbout = ref<string | null>(null)
const gateFindingTitle = ref('')
const gateFindingPriority = ref<number | null>(1)
const gateAbout = ref<string | null>(null)

const authorizable = computed(() =>
    props.task?.status === 'PENDING_INTAKE' || props.task?.status === 'AWAITING_COORDINATOR')
const completable = computed(() =>
    ['AWAITING_COORDINATOR', 'PENDING_INTAKE', 'QUEUED', 'ON_HOLD'].includes(props.task?.status))
const canDecide = computed(() => DECIDABLE_STATUSES.includes(props.task?.status))

const roleOptions = computed(() => (props.roles ?? [])
    .filter((r: any) => r.active)
    .map((r: any) => ({ label: r.name, value: r.name })))
const priorityOptions = computed(() => Array.from({ length: props.priorityLevels ?? 3 },
    (_, i) => ({ label: `P${i + 1}`, value: i + 1 })))
const fileSpecOptions = INDEXED_TYPES.map(s => ({ label: s === 'TEST_REPORT' ? 'test report' : 'review', value: s }))
// What a finding may be about: the types some active role produces, which is where it will route.
const aboutOptions = computed(() => {
    const specs = new Set<string>()
    for (const r of props.roles ?? []) {
        if (!r.active) continue
        for (const o of r.producesOutputs ?? []) if (o?.specification) specs.add(o.specification)
    }
    return [...specs].sort().map(s => ({ label: s.toLowerCase().replace(/_/g, ' '), value: s }))
})

// The newest round of each findings type, open items first.
const findingRounds = computed(() => INDEXED_TYPES
    .map(spec => ({ spec, release: latestRound(taskDocuments.value, spec) }))
    .filter(r => r.release !== null)
    .map(r => {
        const all = sortFindings(((r.release as DocumentRelease).document?.findings?.findings ?? []) as Finding[])
        return {
            spec: r.spec,
            release: r.release as DocumentRelease,
            findings: [...all.filter(f => f.status === 'OPEN'), ...all.filter(f => f.status !== 'OPEN')],
        }
    }))

const blockers = computed(() => completionBlockers(taskDocuments.value, props.board?.completionPriority ?? null))

function aboutOf (spec: string): string | null {
    return latestRound(taskDocuments.value, spec)?.document?.findings?.about?.specification ?? null
}

function toggleDecide (spec: string, f: Finding) {
    const key = spec + '/' + f.id
    deciding.value = deciding.value === key ? null : key
    decisionWords.value = ''
    decisionPriority.value = f.priority ?? null
}

function decideOne (spec: string, decision: any) {
    emit('decide', { task: props.task, specification: spec, decisions: [decision] })
    deciding.value = null
}

function fileFinding () {
    emit('decide', {
        task: props.task,
        specification: fileSpec.value,
        decisions: [{ action: 'FILE', title: fileTitle.value.trim(), priority: filePriority.value }],
        about: fileAbout.value ? { specification: fileAbout.value } : null,
    })
    fileTitle.value = ''
}

function rejectAtGate () {
    const title = gateFindingTitle.value.trim()
    emit('human-review', {
        task: props.task,
        approve: false,
        note: reviewNote.value,
        findings: title ? [{ action: 'FILE', title, priority: gateFindingPriority.value }] : undefined,
        about: title && gateAbout.value ? { specification: gateAbout.value } : null,
    })
}

watch(() => props.task?.uuid, () => {
    authorizeRole.value = props.task?.role ?? null
    orderDraft.value = props.task?.orderIndex ?? null
    cancelNote.value = ''
    showComplete.value = false
    completeNote.value = ''
    skipRequired.value = false
    blockerWords.value = {}
    deciding.value = null
    fileTitle.value = ''
    fileAbout.value = null
    gateFindingTitle.value = ''
    gateAbout.value = null
}, { immediate: true })

const reviewNote = ref('')
const releaseNote = ref('')
const answers = ref<Record<string, string>>({})
const withdrawn = ref<Record<string, boolean>>({})
const answerAll = ref('')

// The ids the newest question frame is still waiting on.
//
// openQuestions rather than openFindings: the latter flattens every indexed type into one list
// with nothing saying which round an item came from, and it is the questions a human answers.
// Only while the task is parked or with the coordinator. A question can be open while the board
// has the task QUEUED or ASSIGNED to the role that is meant to answer it, and answering then pops
// the frame and re-queues the asker under an agent that is mid-hop -- whose sign-off would fail
// because it no longer holds the task. The server refuses that; this stops the UI offering it.
const answerable = computed<Finding[]>(() => {
    if (!props.task?.questionStack?.length) return []
    if (props.task.status !== 'AWAITING_COORDINATOR' && props.task.status !== 'ON_HOLD') return []
    return (props.task?.openQuestions ?? []) as Finding[]
})

// Either per-id answers or one text for all of them. Nothing else counts as an answer: a release
// with neither only lifts the hold, which is what the server does with it.
const answerPayload = computed(() => {
    const per = answerable.value
        .filter(f => (answers.value[f.id] ?? '').trim().length > 0)
        .map(f => ({
            id: f.id,
            status: withdrawn.value[f.id] ? 'WITHDRAWN' : 'RESOLVED',
            resolution: (answers.value[f.id] ?? '').trim(),
        }))
    return {
        task: props.task,
        answers: per,
        answerAll: per.length ? undefined : (answerAll.value.trim() || undefined),
    }
})

const canAnswer = computed(() =>
    answerPayload.value.answers.length > 0 || !!answerPayload.value.answerAll)

// Documents this task has produced, newest first as the server returns them.
const taskDocuments = computed<DocumentRelease[]>(() => props.task?.documents ?? [])

// Open questions, when there is no answer form showing them: the task is with the role meant to
// answer, and a reader still wants to see what it is waiting on.
const openQuestionGroups = computed(() => groupByPriority((props.task?.openQuestions ?? []) as Finding[]))

// The documents a hop recorded as its outputs. A hop stores uuids and the task carries the
// releases, so they are resolved here rather than holding two shapes of the same thing.
function hopOutputs (rec: any): DocumentRelease[] {
    return outputsOfHop(rec?.outputs, taskDocuments.value)
}

// Per-hop cost, shown inline on the history row rather than in a column: a hop
// that cost nothing to report is the common case, and an always-present column
// of dashes would push the role and agent off the row for no gain.
function hopHasUsage (rec: any): boolean {
    return (rec?.usage?.reports ?? 0) > 0
}

function hopLabel (rec: any): string {
    return costLabel(rec.usage) + ' · ' + formatTokens(totalTokens(rec.usage)) + ' tok'
}

function hopTitle (rec: any): string {
    const u = rec.usage ?? {}
    return `${u.requests ?? 0} requests, ${u.turns ?? 0} turns\n` +
        `in ${formatTokens(u.inputTokens)} · out ${formatTokens(u.outputTokens)} · ` +
        `cache read ${formatTokens(u.cacheReadTokens)} · cache write ${formatTokens(u.cacheWriteTokens)}` +
        (u.costComplete === false ? '\nSome rows had no applicable price: the cost is a lower bound.' : '')
}
watch(() => props.task?.uuid, () => { reviewNote.value = '' })

const terminal = computed(() =>
    props.task?.status === 'COMPLETED' || props.task?.status === 'CANCELLED')

/**
 * The name of a role config, for the question stack.
 *
 * Frames carry role uuids because a name is not an identity -- the same reason the hop records
 * carry one. A human reading "coder asked designer" wants neither uuid, so this resolves from the
 * roles already loaded and falls back to a short uuid when a role has been removed.
 */
function roleName (uuid?: string | null): string {
    if (!uuid) return ''
    const rc = (props.roles ?? []).find((r: any) => r.uuid === uuid)
    return rc?.name ?? uuid.slice(0, 8)
}

// Task queued in a HUMAN-kind role: org admins sign off directly (no claim step).
const humanStageRole = computed(() => {
    if (props.task?.status !== 'QUEUED') return null
    const rc = (props.roles ?? []).find(r => r.name === props.task.role)
    return rc?.kind === 'HUMAN' ? rc : null
})

// Active REQUIRED roles whose most recent sign-off on this task is not PASSED --
// mirrors the server-side completion gate so the gap is visible before "done".
const missingRequired = computed(() => {
    if (!props.task || terminal.value || props.task.childTasks?.length) return []
    return (props.roles ?? [])
        .filter(r => r.active && r.necessity === 'REQUIRED')
        .map(r => r.name)
        .filter((role: string) => {
            const last = [...(props.task.signOffs ?? [])].reverse()
                .find((s: any) => (s.role ?? '').toLowerCase() === role.toLowerCase())
            return !last || last.outcome !== 'PASSED'
        })
})

const resolvedDeps = computed(() => (props.task?.dependsOn ?? [])
    .map((d: string) => props.tasks.find(t => t.uuid === d) ?? { uuid: d, title: 'unknown', status: 'UNKNOWN' }))
const dependents = computed(() => props.task
    ? props.tasks.filter(t => (t.dependsOn ?? []).includes(props.task.uuid)) : [])
const parentTask = computed(() => props.task?.parentTask
    ? props.tasks.find(t => t.uuid === props.task.parentTask) ?? null : null)
const childTasksResolved = computed(() => (props.task?.childTasks ?? [])
    .map((c: string) => props.tasks.find(t => t.uuid === c)).filter(Boolean))

// Sign-offs and returns interleaved chronologically — the task's hop log.
const history = computed(() => {
    if (!props.task) return []
    const rows = [
        ...(props.task.signOffs ?? []).map((rec: any) => ({ kind: 'signoff', rec, at: rec.signedOffAt })),
        ...(props.task.returns ?? []).map((rec: any) => ({ kind: 'return', rec, at: rec.returnedAt })),
    ]
    return rows.sort((a, b) => String(a.at ?? '').localeCompare(String(b.at ?? '')))
})

function agentName (uuid: string | null | undefined): string {
    if (!uuid) return '—'
    return props.agentNames[uuid] ?? shortId(uuid)
}

function label (t: any): string {
    if (t.externalRef?.includes('#')) return '#' + t.externalRef.split('#').pop()
    return (t.title ?? 'task').length > 20 ? t.title.slice(0, 19) + '…' : (t.title ?? 'task')
}

function shortId (u: string): string {
    return u ? u.slice(0, 8) : ''
}

function ts (iso: string | null | undefined): string {
    if (!iso) return '—'
    const d = new Date(iso)
    return isNaN(d.getTime()) ? '—' : d.toLocaleString('en-CA', {
        month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
    })
}

function dur (from: string | null | undefined, to: string | null | undefined): string {
    if (!from) return ''
    const a = new Date(from).getTime()
    const b = to ? new Date(to).getTime() : Date.now()
    if (isNaN(a) || isNaN(b) || b < a) return ''
    const mins = Math.round((b - a) / 60000)
    if (mins < 60) return `${mins}m`
    const h = Math.floor(mins / 60)
    return h < 48 ? `${h}h ${mins % 60}m` : `${Math.floor(h / 24)}d`
}

function statusTone (s: string): string {
    if (s === 'COMPLETED') return 'success'
    if (s === 'ON_HOLD' || s === 'CANCELLED') return 'error'
    if (s === 'ASSIGNED') return 'warning'
    return 'default'
}
</script>

<style scoped lang="scss">
.dhead {
    &__title { font-size: 15px; font-weight: 600; }
    &__sub { display: flex; align-items: center; gap: 8px; margin-top: 4px; font-size: 12px; flex-wrap: wrap; }
}
.dsec {
    &__h {
        font-size: 11px;
        font-weight: 600;
        text-transform: uppercase;
        letter-spacing: 0.05em;
        color: #999;
        margin-bottom: 6px;
    }
}
.fgroup {
    margin-bottom: 8px;
    &__h { display: flex; align-items: center; gap: 6px; margin-bottom: 3px; }
    &__count { font-size: 11px; color: #999; }
}
.frow {
    display: flex; align-items: baseline; flex-wrap: wrap; gap: 7px; font-size: 12.5px;
    padding: 2px 0 2px 10px;
    &__id { font-weight: 600; font-size: 11.5px; }
    &__title { flex: 1; }
    &__loc { font-size: 11px; color: #999; }
    &__dec { font-size: 10.5px; color: #8a8; }
    &--closed { opacity: 0.6; }
}
.fedit { width: 100%; padding: 4px 0 6px 10px; }
.drow {
    display: flex; align-items: baseline; flex-wrap: wrap; gap: 7px; font-size: 12.5px;
    padding: 3px 0;
    &__label { font-weight: 600; }
    &__path { font-size: 11.5px; }
    &__path--plain { color: #777; }
    &__commit { font-size: 11px; color: #999; }
}
.deprow { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; margin-bottom: 4px; }
.deplab { font-size: 10px; text-transform: uppercase; letter-spacing: 0.05em; color: #999; min-width: 52px; }
.depclick { cursor: pointer; }
.hist {
    &__row {
        padding: 6px 0;
        border-bottom: 1px solid rgba(128, 128, 128, 0.12);
        font-size: 12.5px;
        display: flex;
        align-items: baseline;
        flex-wrap: wrap;
        gap: 7px;
        &--active { border-left: 3px solid #d9a24a; padding-left: 8px; }
        &:last-child { border-bottom: none; }
    }
    &__role { font-weight: 600; }
    &__agent { color: #666; }
    &__time { color: #999; font-size: 11.5px; }
    &__pv { font-size: 10.5px; color: #999; background: rgba(128, 128, 128, 0.1); padding: 0 5px; border-radius: 4px; }
    // Pushed to the right so the hop reads role/agent/time first and cost last:
    // the money is the qualifier on the hop, not its headline.
    &__usage { margin-left: auto; font-size: 11.5px; color: #777; white-space: nowrap; }
    &__outputs { width: 100%; display: flex; flex-wrap: wrap; gap: 8px; padding-left: 2px; margin-top: 3px; }
    &__output { font-size: 11.5px; color: #666; }
    &__note { width: 100%; color: #555; font-size: 12px; padding-left: 2px; }
}
.qstack {
    font-size: 11.5px;
    &__row { display: flex; gap: 8px; align-items: baseline; padding: 2px 0; flex-wrap: wrap; }
    &__depth { color: #999; font-family: monospace; }
    &__link { font-size: 11px; }
    &__time { color: #999; margin-left: auto; }
    &__note { color: #b0854a; font-size: 11px; margin-top: 4px; }
}
.shist {
    font-size: 11.5px;
    &__row { display: flex; gap: 8px; align-items: baseline; padding: 2px 0; flex-wrap: wrap; }
    &__time { color: #999; font-family: monospace; }
    &__arrow { color: #555; }
    &__trig { font-size: 10px; color: #888; background: rgba(128, 128, 128, 0.1); padding: 0 4px; border-radius: 4px; }
    &__by { color: #777; font-size: 10.5px; }
    &__note { color: #666; font-size: 10.5px; font-style: italic; }
    &__dur { color: #b0854a; font-size: 10.5px; }
}
.holdmeta { font-size: 11.5px; color: #888; margin-top: 4px; white-space: pre-wrap; }
.qans { margin-top: 10px; border-top: 1px solid #2a2a2a; padding-top: 8px; }
.qans__row { margin-bottom: 8px; }
.qans__id { display: flex; gap: 6px; align-items: baseline; margin-bottom: 3px; }
.qans__tag { font-family: monospace; font-size: 11px; color: #9ab; }
.qans__title { font-size: 12px; color: #bbb; }
.prov { font-size: 12px; color: #777; div { margin-bottom: 3px; } }
.sesschip { font-size: 10.5px; margin-right: 4px; background: rgba(128, 128, 128, 0.1); padding: 0 5px; border-radius: 4px; }
.prlink2 { font-size: 12.5px; }
.empty { color: #888; font-size: 12.5px; }
</style>
