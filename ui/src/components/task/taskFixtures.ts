// Fixture tasks for the task page, the drawer and their section components' specs. Shaped as the
// agentTask / agentTasksOfBoard selection returns them.

function finding (id: string, priority: number, status: string, title: string, extra: Record<string, any> = {}) {
    return { id, priority, status, title, location: null, resolvedBy: null, resolution: null,
        decidedBy: null, decidedIn: null, decidedAt: null, ...extra }
}

function round (uuid: string, spec: string, n: number, verdict: string, findings: any[], extra: Record<string, any> = {}) {
    return {
        uuid, version: String(n), lifecycle: 'DRAFT', component: 'c1', createdDate: `2026-09-2${n}T10:00:00Z`,
        sourceCodeEntryDetails: { commit: 'abcdef0123456789', commitMessage: 'm', vcsRepository: { uri: 'github.com/relizaio/docs', name: 'docs' } },
        document: {
            specification: spec, path: `reviews/t1/${spec.toLowerCase()}-${n}.md`, digest: null, mediaType: 'text/markdown',
            indexPath: null, task: 't1', session: 's1', round: n,
            findings: { kind: spec, round: n, verdict, counts: null, findings, about: null },
            ...extra,
        },
    }
}

const longLocation = { path: 'backend/src/main/java/io/reliza/service/AgentRoutingService.java', line: 412, ref: 'eb2921f0' }

/** Three review rounds, a test report, a design; the newest review round is what the page shows. */
export function richDocuments () {
    return [
        round('r3', 'REVIEW_FINDINGS', 3, 'REJECTED', [
            finding('F-4', 2, 'OPEN', 'The served prompt block repeats the heading when a role prompt already ends with a blank line',
                { location: longLocation }),
            finding('F-1', 1, 'RESOLVED', 'Hold reason omits the rule'),
        ]),
        round('r2', 'REVIEW_FINDINGS', 2, 'REJECTED', [finding('F-1', 1, 'OPEN', 'Hold reason omits the rule')]),
        round('r1', 'REVIEW_FINDINGS', 1, 'REJECTED', [finding('F-1', 1, 'OPEN', 'Hold reason omits the rule')]),
        round('tr1', 'TEST_REPORT', 1, 'PASSED', [finding('T-1', 3, 'OPEN', 'Screenshot missing')]),
        { ...round('a1', 'ARCHITECTURE', 1, 'PASSED', []), document: { ...round('a1', 'ARCHITECTURE', 1, 'PASSED', []).document, findings: null, path: 'design/t1/architecture-1.md' } },
    ]
}

const usage = { inputTokens: 1200, outputTokens: 300, cacheReadTokens: 0, cacheWriteTokens: 0, requests: 3, turns: 2,
    reports: 1, derivedCostMicros: 420000, costComplete: true }

export function richTask (over: Record<string, any> = {}) {
    return {
        uuid: 't1', board: 'b1', org: 'o1', externalRef: 'github:relizaio/rearm#42', title: 'Task page per task',
        sourceUrl: 'https://github.com/relizaio/rearm/issues/42', status: 'ON_HOLD', role: 'coder', orderIndex: 3,
        dependsOn: ['t0'], requireHumanReview: false,
        hold: { level: 'OPERATOR', kind: 'MANUAL', gateRole: null, reason: 'stopped by no progress: [F-1] stayed OPEN', heldBy: { kind: 'SYSTEM', uuid: null, name: 'routing' }, heldAt: '2026-09-24T10:00:00Z' },
        assignment: null,
        signOffs: [
            { role: 'architect', agent: 'a1', session: 's1', assignedAt: '2026-09-21T09:00:00Z', signedOffAt: '2026-09-21T10:00:00Z', outcome: 'PASSED', note: 'design', promptVersion: 'abc123', reviewedBy: null, outputs: ['a1'], usage },
            { role: 'reviewer', agent: 'a2', session: 's2', assignedAt: '2026-09-23T09:00:00Z', signedOffAt: '2026-09-23T10:00:00Z', outcome: 'REJECTED', note: 'F-1 open', promptVersion: 'def456', reviewedBy: null, outputs: ['r3'], usage },
        ],
        returns: [{ role: 'coder', agent: 'a3', session: 's3', reason: 'BLOCKED', description: 'needs a decision', returnedAt: '2026-09-22T10:00:00Z', outputs: [], usage: null }],
        usage,
        documents: richDocuments(),
        openFindings: [],
        openQuestions: [finding('q1', 1, 'OPEN', 'Which branch does the page link to?')],
        parentTask: 'tp', childTasks: ['tc1'], sessions: ['s1', 's2', 's3'], registeredBySession: 's0',
        statusHistory: [
            { from: null, to: 'PENDING_INTAKE', at: '2026-09-20T10:00:00Z', trigger: 'REGISTER', actor: null, note: null },
            { from: 'PENDING_INTAKE', to: 'QUEUED', at: '2026-09-20T11:00:00Z', trigger: 'AUTHORIZE', actor: { kind: 'USER', uuid: 'u1', name: 'pavel' }, note: 'go' },
            { from: 'QUEUED', to: 'ON_HOLD', at: '2026-09-24T10:00:00Z', trigger: 'HOLD', actor: null, note: null },
        ],
        orderSetBy: null, orderSetAt: null, requiredRolesSkipped: false, reopenedAt: null, reopenCount: 0,
        pullRequests: [{ url: 'https://github.com/relizaio/rearm/pull/396', state: 'OPEN', targetBranch: 'main', mergedDate: null, registered: true }],
        budgetMicros: null, coordinatorEstimateMicros: null, requiredStrength: null, strengthSetBy: null, strengthSetAt: null,
        questionStack: [{ askingRole: 'rc-coder', askingSession: 's3', askingAgent: 'a3', questionsRelease: 'q-rel', answeringRole: null, askedAt: '2026-09-24T09:00:00Z' }],
        prUrls: ['https://github.com/relizaio/rearm/pull/396'],
        createdDate: '2026-09-20T10:00:00Z', completedAt: null,
        ...over,
    }
}

export const fixtureRoles = [
    { uuid: 'rc-arch', name: 'architect', active: true, necessity: 'REQUIRED', kind: 'AGENT', producesOutputs: [{ specification: 'ARCHITECTURE' }] },
    { uuid: 'rc-coder', name: 'coder', active: true, necessity: 'REQUIRED', kind: 'AGENT', producesOutputs: [] },
    { uuid: 'rc-rev', name: 'reviewer', active: true, necessity: 'REQUIRED', kind: 'AGENT', producesOutputs: [{ specification: 'REVIEW_FINDINGS' }] },
    { uuid: 'rc-human', name: 'signoff', active: true, necessity: 'OPTIONAL', kind: 'HUMAN', prompt: 'look it over', producesOutputs: [] },
]

export function fixtureTasks (task: any) {
    return [
        task,
        { uuid: 't0', title: 'Backend query', status: 'COMPLETED', externalRef: 'github:relizaio/rearm#41', dependsOn: [] },
        { uuid: 'tp', title: 'Board gaps', status: 'QUEUED', externalRef: null, dependsOn: [] },
        { uuid: 'tc1', title: 'Screenshots', status: 'QUEUED', externalRef: 'github:relizaio/rearm#43', dependsOn: [] },
        { uuid: 't9', title: 'Depends on the page', status: 'QUEUED', externalRef: 'github:relizaio/rearm#44', dependsOn: ['t1'] },
    ]
}

/** The variants that between them show every section the drawer had. */
export function fixtureVariants () {
    return {
        heldWithQuestions: richTask(),
        humanGate: richTask({ status: 'ON_HOLD', questionStack: [], hold: { level: 'OPERATOR', kind: 'HUMAN_GATE', gateRole: 'reviewer', reason: 'review the pass', heldBy: null, heldAt: '2026-09-24T10:00:00Z' } }),
        queuedHumanStage: richTask({ status: 'QUEUED', role: 'signoff', hold: null, requireHumanReview: true }),
        assigned: richTask({ status: 'ASSIGNED', hold: null, questionStack: [], assignment: { session: 's3', agent: 'a3', role: 'coder', assignedAt: '2026-09-24T09:00:00Z', promptVersion: 'aa11bb22' } }),
        completed: richTask({ status: 'COMPLETED', hold: null, questionStack: [], completedAt: '2026-09-25T10:00:00Z', reopenCount: 1, reopenedAt: '2026-09-24T12:00:00Z' }),
        delivering: richTask({ status: 'DELIVERING', hold: null, questionStack: [] }),
    }
}
