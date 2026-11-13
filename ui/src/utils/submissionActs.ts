// The acts of the draft flow (task FDAW-32 3.5, after TEA-30 3.5.5), used by the release page's package
// section: Start, Rebuild, Discard, Confirm, Return to draft, Finalize, and Cancel of a Final head. One act
// at a time per page (`busy`). Confirm and Finalize send the revision of the card that was clicked, read
// before the dialog opens, and require the statement to be ticked; Discard, Return to draft and Cancel
// require a reason. Every dialog is one SweetAlert2 options object with a constant title in `titleText`
// and the message in `text`: names reach it through teaOneLine, and a refused act shows the server's
// sentence as text (FDAW-32 3.0). No success dialog and no further mutation follow a refusal.
import { ref } from 'vue'
import Swal from 'sweetalert2'
import submissionQueries from './submissionQueries'
import {
    SubmissionNames, submissionActErrorText, submissionCancelConfirm, submissionReasonError, submissionWrittenText,
} from './submissions'
import {
    CONFIRMATION_STATEMENT, STATEMENT_REQUIRED, draftConfirmConfirm, draftDiscardConfirm, draftFinalizeConfirm, draftReturnConfirm,
    draftStartVariables, type DraftStartForm,
} from './submissionDrafts'

export interface SubmissionActsDeps {
    client: { query: (o: any) => Promise<any>, mutate: (o: any) => Promise<any> }
    reload: () => unknown
}

function reasonOf (value: unknown): string {
    return typeof value === 'string' ? value.trim() : ''
}

/** The reason textarea of Discard, Return to draft and Cancel. */
function reasonInput (cancelButtonText: string) {
    return { input: 'textarea' as const, inputLabel: 'Reason (required)', inputAttributes: { maxlength: '2048' },
        inputValidator: (v: string) => submissionReasonError(v, true), showCancelButton: true, cancelButtonText,
        confirmButtonColor: '#d33' }
}

/** The statement checkbox of Confirm and Finalize. */
function statementInput () {
    return { input: 'checkbox' as const, inputValue: 0, inputPlaceholder: CONFIRMATION_STATEMENT,
        inputValidator: (v: any) => (v ? undefined : STATEMENT_REQUIRED), showCancelButton: true, cancelButtonText: 'Cancel' }
}

function errorDialog (err: any) {
    console.error(err)
    Swal.fire({ titleText: 'Error!', text: submissionActErrorText(err), icon: 'error' })
}

export function useSubmissionActs (deps: SubmissionActsDeps) {
    const busy = ref(false)

    /** Sends one mutation; the act's result, or null after the error dialog. */
    async function send (mutation: any, variables: Record<string, any>, field: string): Promise<any | null> {
        try {
            const resp: any = await deps.client.mutate({ mutation, variables })
            return resp?.data?.[field] ?? {}
        } catch (err: any) {
            errorDialog(err)
            return null
        }
    }

    /** One act at a time: a call while another runs sends nothing and answers undefined. */
    async function oneAtATime<T> (run: () => Promise<T>): Promise<T | undefined> {
        if (busy.value) return undefined
        busy.value = true
        try {
            return await run()
        } finally {
            busy.value = false
        }
    }

    /** Start a draft from the panel's form, then request its first rebuild; true when startDraft succeeded. */
    async function start (release: string, kind: string, form: DraftStartForm): Promise<boolean> {
        const answered = await oneAtATime(async () => {
            const started = await send(submissionQueries.StartDraftGql, draftStartVariables(release, kind, form), 'startDraft')
            if (started) {
                const draft = started?.draft?.uuid
                if (draft) await send(submissionQueries.RebuildDraftGql, { draft }, 'rebuildDraft')
            }
            await deps.reload()
            return Boolean(started)
        })
        return answered === true
    }

    async function rebuild (draft: any) {
        await oneAtATime(async () => {
            await send(submissionQueries.RebuildDraftGql, { draft: draft.uuid }, 'rebuildDraft')
            await deps.reload()
        })
    }

    async function discard (draft: any, names: SubmissionNames) {
        await oneAtATime(async () => {
            const answer: any = await Swal.fire({ ...draftDiscardConfirm(draft, names), icon: 'warning',
                ...reasonInput('Keep draft') })
            if (!answer?.isConfirmed) return
            await send(submissionQueries.DiscardDraftGql, { draft: draft.uuid, reason: reasonOf(answer.value) }, 'discardDraft')
            await deps.reload()
        })
    }

    async function confirm (draft: any, names: SubmissionNames) {
        await oneAtATime(async () => {
            const expectedRevision = draft.revision
            const answer: any = await Swal.fire({ ...draftConfirmConfirm(draft, names), icon: 'question', ...statementInput() })
            if (!answer?.isConfirmed) return
            await send(submissionQueries.ConfirmDraftGql, { draft: draft.uuid, expectedRevision }, 'confirmDraft')
            await deps.reload()
        })
    }

    async function returnToEditing (draft: any, names: SubmissionNames) {
        await oneAtATime(async () => {
            const answer: any = await Swal.fire({ ...draftReturnConfirm(draft, names), icon: 'warning', ...reasonInput('Cancel') })
            if (!answer?.isConfirmed) return
            await send(submissionQueries.ReturnDraftToEditingGql, { draft: draft.uuid, reason: reasonOf(answer.value) },
                'returnDraftToEditing')
            await deps.reload()
        })
    }

    async function finalize (draft: any, names: SubmissionNames, secondConfirmer: boolean | null) {
        await oneAtATime(async () => {
            const expectedRevision = draft.revision
            const answer: any = await Swal.fire({ ...draftFinalizeConfirm(draft, names, secondConfirmer), icon: 'question',
                ...statementInput() })
            if (!answer?.isConfirmed) return
            const result = await send(submissionQueries.FinalizeDraftGql, { draft: draft.uuid, expectedRevision }, 'finalizeDraft')
            if (result) Swal.fire({ titleText: 'Submission', text: submissionWrittenText(result), icon: 'success' })
            await deps.reload()
        })
    }

    async function cancel (head: any, names: SubmissionNames) {
        await oneAtATime(async () => {
            const answer: any = await Swal.fire({ ...submissionCancelConfirm(head, names), icon: 'warning',
                ...reasonInput('Keep submission') })
            if (!answer?.isConfirmed) return
            const result = await send(submissionQueries.CancelSubmissionGql, { submission: head.uuid, reason: reasonOf(answer.value) },
                'cancelSubmission')
            if (result) Swal.fire({ titleText: 'Submission', text: 'Cancelled.', icon: 'success' })
            await deps.reload()
        })
    }

    return { busy, start, rebuild, discard, confirm, returnToEditing, finalize, cancel }
}
