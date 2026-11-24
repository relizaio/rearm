// The blocking "Sign-in refused" dialog of a license seat refusal (task TPF-168, TPF-165 D8 and 6.2),
// and the GraphQL error-link handler that opens it from ANY operation: the `user` query at app load
// (SEAT_FULL, NO_ROLE, OVER_LIMIT), a tab cut off mid-session (OVER_LIMIT on its next request), and a
// refused invitation join or first organization (SEAT_FULL, membership wording).
//
// The server's text is shown verbatim as plain text (Swal `text`, never `html`); the only way out is
// Sign out, so the person can switch accounts.

import Swal from 'sweetalert2'
import { CombinedGraphQLErrors } from '@apollo/client/errors'
import { SEAT_REFUSED_SIGN_OUT, SEAT_REFUSED_TITLE, seatRefusalOf, type SeatRefusal } from './licenseSeats'

let showing: Promise<void> | null = null

/**
 * Opens the dialog for `refusal`. While one is open, a further refusal (every request of a cut-off
 * tab answers one) returns the open dialog's promise instead of stacking another.
 */
export function showSeatRefusal (refusal: SeatRefusal): Promise<void> {
    if (showing) return showing
    showing = (async () => {
        const result = await Swal.fire({
            title: SEAT_REFUSED_TITLE,
            text: refusal.message,
            icon: 'error',
            confirmButtonText: SEAT_REFUSED_SIGN_OUT,
            showConfirmButton: true,
            showCancelButton: false,
            allowOutsideClick: false,
            allowEscapeKey: false
        })
        if (result?.isConfirmed) {
            // Loaded on use, not at import: keycloak initialises at module load (a top-level
            // await on the auth server), and commonFunctions, which every page imports, imports
            // this module. In the app it is already loaded by the GraphQL client.
            const { default: keycloak } = await import('./keycloak')
            await keycloak.logout()
        }
    })().finally(() => { showing = null })
    return showing
}

/** True while the dialog is open (for callers that would otherwise show their own error over it). */
export function seatRefusalShowing (): boolean {
    return showing !== null
}

/**
 * The error-link handler: a response carrying a seat refusal opens the dialog. Nothing else is
 * touched; the operation's own result and error still reach its caller.
 */
export function onSeatRefusalError ({ error, result }: { error?: any, result?: any }): void {
    const entries = CombinedGraphQLErrors.is(error) ? error.errors : (result?.errors ?? null)
    const refusal = seatRefusalOf(entries)
    if (refusal) void showSeatRefusal(refusal)
}
