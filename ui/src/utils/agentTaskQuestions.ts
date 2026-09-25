// Which open questions a person may answer on a task, and the answer they send.
import type { Finding } from './agentDocuments'

/**
 * The ids the newest question frame is still waiting on.
 *
 * openQuestions rather than openFindings: the latter flattens every indexed type into one list
 * with nothing saying which round an item came from, and it is the questions a human answers.
 * Only while the task is parked or with the coordinator. A question can be open while the board
 * has the task QUEUED or ASSIGNED to the role that is meant to answer it, and answering then pops
 * the frame and re-queues the asker under an agent that is mid-hop -- whose sign-off would fail
 * because it no longer holds the task. The server refuses that; this stops the UI offering it.
 */
export function answerableQuestions (task: any): Finding[] {
    if (!task?.questionStack?.length) return []
    if (task.status !== 'AWAITING_COORDINATOR' && task.status !== 'ON_HOLD') return []
    return (task?.openQuestions ?? []) as Finding[]
}

export type AnswerPayload = {
    task: any
    answers: { id: string, status: string, resolution: string }[]
    answerAll?: string
}

/**
 * Either per-id answers or one text for all of them. Nothing else counts as an answer: a release
 * with neither only lifts the hold, which is what the server does with it.
 */
export function answerPayloadOf (task: any, answerable: Finding[], answers: Record<string, string>,
    withdrawn: Record<string, boolean>, answerAll: string): AnswerPayload {
    const per = answerable
        .filter(f => (answers[f.id as string] ?? '').trim().length > 0)
        .map(f => ({
            id: f.id as string,
            status: withdrawn[f.id as string] ? 'WITHDRAWN' : 'RESOLVED',
            resolution: (answers[f.id as string] ?? '').trim(),
        }))
    return {
        task,
        answers: per,
        answerAll: per.length ? undefined : (answerAll.trim() || undefined),
    }
}
