// The three submission acts (task TEA-30, design 3.5.5), shared by the product page's Submissions
// tab and the release page's section. One act at a time per page (`busy`); Create and Resubmit ask
// the server's preview first and confirm on it; Resubmit and Withdraw require a reason; Resubmit
// sends the version the page shows as expectedLatestVersion. Every dialog is the SweetAlert2 object
// form with the message in `text`: preview messages and act refusals embed component and
// perspective names other members wrote, and the positional second argument is HTML (design 3.8, D18).
import { ref } from 'vue'
import Swal from 'sweetalert2'
import submissionQueries from './submissionQueries'
import {
    SubmissionNames, submissionActErrorText, submissionWrittenText, submissionCreateConfirm,
    submissionKindDisabledText, submissionReasonError, submissionResubmitConfirm, submissionWithdrawConfirm,
} from './submissions'

export interface SubmissionActsDeps {
    client: { query: (o: any) => Promise<any>, mutate: (o: any) => Promise<any> }
    reload: () => unknown
    names?: () => Record<string, string>
}

function reasonOf (value: unknown): string | null {
    const r = typeof value === 'string' ? value.trim() : ''
    return r || null
}

export function useSubmissionActs (deps: SubmissionActsDeps) {
    const busy = ref(false)
    const perspectiveNames = () => deps.names?.() ?? {}

    async function preview (release: string, kind: string): Promise<any | null> {
        try {
            const resp: any = await deps.client.query({ query: submissionQueries.SubmissionPreviewGql,
                variables: { release, kind }, fetchPolicy: 'no-cache' })
            return resp.data.submissionPreview
        } catch (err: any) {
            console.error(err)
            Swal.fire({ title: 'Error!', text: submissionActErrorText(err), icon: 'error' })
            await deps.reload()
            return null
        }
    }

    // A preview that refuses: KIND_DISABLED (the kind was switched off between the page's read and the
    // click) is explained with the client sentence and the page re-reads, so its cell turns to
    // "Not set up"; every other refusal shows the server's message.
    async function refusedPreview (title: string, p: any, kind: string, componentType: string | null | undefined) {
        if (p.refusal === 'KIND_DISABLED') {
            Swal.fire({ titleText: title, text: submissionKindDisabledText(kind, componentType), icon: 'error' })
            await deps.reload()
            return
        }
        Swal.fire({ titleText: title, text: p.message ?? '', icon: 'error' })
    }

    async function act (mutation: any, variables: Record<string, any>, field: string, success: (r: any) => string) {
        try {
            const resp: any = await deps.client.mutate({ mutation, variables })
            const result = resp.data[field]
            Swal.fire({ title: 'Submission', text: success(result), icon: 'success' })
        } catch (err: any) {
            console.error(err)
            Swal.fire({ title: 'Error!', text: submissionActErrorText(err), icon: 'error' })
        }
        await deps.reload()
    }

    async function create (release: string, kind: string, names: SubmissionNames) {
        if (busy.value) return
        busy.value = true
        try {
            const p = await preview(release, kind)
            if (!p) return
            if (!p.canSubmit) {
                await refusedPreview('Cannot create this submission', p, kind, names?.componentType)
                return
            }
            const answer: any = await Swal.fire({ ...submissionCreateConfirm(p, kind, names, perspectiveNames()),
                icon: 'question', input: 'textarea', inputLabel: 'Reason (optional)',
                inputAttributes: { maxlength: '2048' }, inputValidator: (v: string) => submissionReasonError(v, false),
                showCancelButton: true, cancelButtonText: 'Cancel' })
            if (!answer?.isConfirmed) return
            await act(submissionQueries.CreateSubmissionGql, { release, kind, reason: reasonOf(answer.value) },
                'createSubmission', r => submissionWrittenText(r))
        } finally {
            busy.value = false
        }
    }

    async function resubmit (head: any, names: SubmissionNames) {
        if (busy.value) return
        busy.value = true
        try {
            const p = await preview(head.release, head.kind)
            if (!p) return
            if (!p.canSubmit) {
                await refusedPreview('Cannot resubmit this submission', p, head.kind, head.componentType ?? names?.componentType)
                return
            }
            const answer: any = await Swal.fire({ ...submissionResubmitConfirm(p, head.kind, names, perspectiveNames()),
                icon: 'question', input: 'textarea', inputLabel: 'Reason (required)',
                inputAttributes: { maxlength: '2048' }, inputValidator: (v: string) => submissionReasonError(v, true),
                showCancelButton: true, cancelButtonText: 'Cancel' })
            if (!answer?.isConfirmed) return
            await act(submissionQueries.ResubmitSubmissionGql,
                { submission: head.uuid, reason: reasonOf(answer.value), expectedLatestVersion: head.latestVersion },
                'resubmitSubmission', r => submissionWrittenText(r))
        } finally {
            busy.value = false
        }
    }

    async function withdraw (head: any, names: SubmissionNames) {
        if (busy.value) return
        busy.value = true
        try {
            const answer: any = await Swal.fire({ ...submissionWithdrawConfirm(head, names), icon: 'warning',
                input: 'textarea', inputLabel: 'Reason (required)', inputAttributes: { maxlength: '2048' },
                inputValidator: (v: string) => submissionReasonError(v, true),
                showCancelButton: true, cancelButtonText: 'Cancel', confirmButtonColor: '#d33' })
            if (!answer?.isConfirmed) return
            await act(submissionQueries.WithdrawSubmissionGql, { submission: head.uuid, reason: reasonOf(answer.value) },
                'withdrawSubmission', r => r?.outcome === 'UNCHANGED' ? 'Already cancelled.' : 'Cancelled.')
        } finally {
            busy.value = false
        }
    }

    return { busy, create, resubmit, withdraw }
}
