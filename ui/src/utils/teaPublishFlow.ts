// The release page's Publish, Re-publish and Share with customers (task FDAW-39, design 3.7 and 3.8),
// out of ReleaseView.vue so it runs without mounting the page. For a product that cascades it first
// reads the preview's children under a progress dialog, so the confirmation can name every component
// release left behind; "Turn on for both" runs the settings sequence of teaTurnOn.ts before the act.
// Every dialog is one object argument with its names in `text` only; a share catches its own errors.

import { loadTeaPublishChildren } from './teaDocuments'
import { teaTurnOnChildren } from './teaTurnOn'
import {
    TEA_ERROR_TITLE, TEA_NOT_PUBLISHED_TITLE, TEA_SHARE_COMMENT, type TeaAct, teaChildrenReadErrorText,
    teaChildrenReadProgress, teaNotStartedText, teaPartialChildren, type TeaPartial, teaPublishConfirm, type TeaPublishNames,
    teaPublishSummary, type TeaShare, teaShareConfirm, teaShareErrorText, teaTurnOnProgress, teaTurnOnStopText,
} from './teaPublication'

export interface TeaPublishFlowDeps {
    client: { query (options: any): Promise<any>, mutate (options: any): Promise<any> }
    /** SweetAlert2's fire, always called with one object. */
    fire: (options: any) => Promise<any>
    /** Shows a dialog that cannot be dismissed, without waiting for it; the next dialog replaces it. */
    progress: (options: any) => void
    /** The page's act runner (withTeaProgress); it may return without calling `run` while another act runs. */
    withProgress: (act: TeaAct, view: any, run: () => Promise<void>) => Promise<void>
    isOrgAdmin: boolean
    org: string
    installationType?: string | null
    componentOf: (release: string) => string | null | undefined
}

export interface TeaPublishFlowArgs {
    view: any
    names: TeaPublishNames
    mutation: any
    field: string
    share?: TeaShare | null
}

/** Publish, Re-publish or Share: the list, the confirmation, the sequence when asked, then the act. */
export async function runTeaPublishFlow (deps: TeaPublishFlowDeps, { view, names, mutation, field, share = null }: TeaPublishFlowArgs): Promise<void> {
    if (!view) return
    const { client, fire, progress } = deps

    let partial: TeaPartial | null = null
    if (view.kind === 'PRODUCT_RELEASE' && view.cascades) {
        progress(teaChildrenReadProgress())
        try {
            const { children } = await loadTeaPublishChildren(client, view.release)
            if (children !== null) partial = teaPartialChildren(children, { isOrgAdmin: deps.isOrgAdmin, componentOf: deps.componentOf })
        } catch (err: any) {
            await fire({ titleText: TEA_NOT_PUBLISHED_TITLE, text: teaChildrenReadErrorText(err), icon: 'error' })
            return
        }
    }

    const answer: any = await fire(share ? teaShareConfirm(view, names, share, partial) : teaPublishConfirm(view, names, partial))
    let saved: string[] = []
    if (answer?.isDenied === true && partial?.offer.length) {
        progress(teaTurnOnProgress())
        const result = await teaTurnOnChildren({
            client,
            org: deps.org,
            installationType: deps.installationType,
            componentOf: deps.componentOf,
            names: names.perspectives ?? {},
            confirm: async (c: any) => {
                const reply: any = await fire({ ...c, icon: 'warning', showCancelButton: true, cancelButtonText: 'Cancel' })
                if (reply?.isConfirmed !== true) return false
                progress(teaTurnOnProgress())
                return true
            },
        }, partial.offer)
        if (result.stop) {
            await fire({ titleText: TEA_NOT_PUBLISHED_TITLE, text: teaTurnOnStopText(result), icon: 'info' })
            return
        }
        saved = result.saved
    } else if (answer?.isConfirmed !== true) {
        return
    }

    const act: TeaAct = field === 'republishReleaseOnTea' ? 'republish' : 'publish'
    let started = false
    await deps.withProgress(act, view, async () => {
        started = true
        let resp: any
        try {
            resp = await client.mutate({
                mutation,
                variables: share ? { release: view.release, comment: TEA_SHARE_COMMENT } : { release: view.release },
                fetchPolicy: 'no-cache',
            })
        } catch (err: any) {
            // A share words its own refusal, as text; a plain publish keeps the page's older error path.
            if (!share) throw err
            await fire({ titleText: TEA_ERROR_TITLE, text: teaShareErrorText(err), icon: 'error' })
            return
        }
        await fire({ title: 'TEA', text: teaPublishSummary(resp.data[field], share ? { worded: true } : {}), icon: 'success' })
    })
    if (!started) await fire({ titleText: TEA_NOT_PUBLISHED_TITLE, text: teaNotStartedText(saved), icon: 'info' })
}
