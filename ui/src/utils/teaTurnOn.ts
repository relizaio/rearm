// "Turn on for both" (task FDAW-39, design 3.7): before a product is published, turn TEA publishing on
// in the own COMPONENT row of each offered child, one child after the other, with the settings
// editor's read, dry-run, confirmations and save. No new mutation and no new gate: each save is the
// editor's save under the editor's server gates. The sequence never rejects; every error is a stop,
// and the saves made before a stop stand.

import graphqlQueries from './graphqlQueries'
import { graphqlRefusalText } from './graphqlErrorText'
import { loadTeaDocument, teaCoreDocument } from './teaDocuments'
import { teaConfirmBeforeSave, teaFormFromView, teaProfileInput, teaTurnOnConfirm } from './teaProfile'
import { teaChildName, teaServerAnswered, teaTurnOnPublicConfirm, teaTurnOnStopSentence } from './teaPublication'

export interface TeaTurnOnDeps {
    client: { query (options: any): Promise<any>, mutate (options: any): Promise<any> }
    org: string
    installationType?: string | null
    componentOf: (release: string) => string | null | undefined
    names: Record<string, string>
    /** Shows one confirmation; true only when it was accepted. */
    confirm: (c: any) => Promise<boolean>
}

export type TeaTurnOnStopKind = 'changed' | 'declined' | 'refused' | 'unanswered'

export interface TeaTurnOnResult {
    saved: string[]
    stop: { kind: TeaTurnOnStopKind, child: string, text: string } | null
}

/** Turns publishing on for each offered child in order; stops at the first child that cannot be saved. */
export async function teaTurnOnChildren (deps: TeaTurnOnDeps, offer: any[]): Promise<TeaTurnOnResult> {
    const saved: string[] = []
    for (const child of offer ?? []) {
        const name = teaChildName(child)
        const stop = (kind: TeaTurnOnStopKind, refusal = ''): TeaTurnOnResult =>
            ({ saved, stop: { kind, child: name, text: teaTurnOnStopSentence(kind, name, refusal) } })
        const object = deps.componentOf(child?.release)

        // 1. The component's editor view, through the drift fallback.
        let view: any
        let additive: boolean
        try {
            const read = await loadTeaDocument(deps.client, {
                query: graphqlQueries.TeaProfileEditorViewGql,
                variables: { org: deps.org, scope: 'COMPONENT', object },
                fetchPolicy: 'no-cache',
            })
            view = read.data?.teaProfileEditorView
            additive = read.additive
        } catch (err: any) {
            return stop('refused', graphqlRefusalText(err))
        }

        // 2. Only the row the preview named, still an override with publishing off.
        const stored = view?.stored
        if (!stored || stored.uuid !== child?.ownRow || stored.mode === 'FOLLOW_PERSPECTIVE' || stored.publishing !== 'DISABLED') {
            return stop('changed')
        }

        // 3. The stored row with publishing on; every other field as stored.
        const input = teaProfileInput({ ...teaFormFromView(view, 'COMPONENT'), publishing: 'ENABLED' }, 'COMPONENT',
            deps.installationType, additive)
        const variables = { org: deps.org, scope: 'COMPONENT', object, profile: input }

        // 4. The dry-run.
        let exposure: any
        try {
            const read = await loadTeaDocument(deps.client, {
                query: graphqlQueries.TeaProfileExposureChangeGql,
                variables,
                fetchPolicy: 'no-cache',
            })
            exposure = read.data?.teaProfileExposureChange
        } catch (err: any) {
            return stop('refused', graphqlRefusalText(err))
        }

        // 5. The Public confirmation for a PUBLIC row whatever the dry-run says (F-8), else the editor's;
        // never both. Then the turn-on confirmation.
        const publicConfirm = input.visibility === 'PUBLIC'
            ? teaTurnOnPublicConfirm(child, input, exposure)
            : teaConfirmBeforeSave(exposure, 'component', deps.names)
        if (publicConfirm && !await deps.confirm(publicConfirm)) return stop('declined')
        const turnOn = teaTurnOnConfirm(view, input, exposure, 'component', deps.names)
        if (turnOn && !await deps.confirm(turnOn)) return stop('declined')

        // 6. The save, as the editor sends it. An error the server did not answer is not known to be refused.
        try {
            await deps.client.mutate({
                mutation: additive ? graphqlQueries.SaveTeaProfileGql : teaCoreDocument(graphqlQueries.SaveTeaProfileGql),
                variables,
            })
        } catch (err: any) {
            return teaServerAnswered(err) ? stop('refused', graphqlRefusalText(err)) : stop('unanswered')
        }
        saved.push(name)
    }
    return { saved, stop: null }
}
