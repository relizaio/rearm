// The one deletion path of a named SBOM profile (task TEA-30, design 3.3.2 to 3.3.4): the editor's
// Delete, the catalog row's Delete and the overrides' "Remove override" all run it, so the
// confirmation and the refusal read the same everywhere. The confirmation is sbomDeleteConfirm's
// (a constant title, the name in `text`); a refusal, the server's consumer-guard sentence that names
// the settings still naming the profile, is shown verbatim as the `text` of the error dialog, never
// as the positional second argument, which SweetAlert2 renders as HTML (design 3.8, D18).
import Swal from 'sweetalert2'
import profilesQueries from './profilesQueries'
import { graphqlRefusalText } from './graphqlErrorText'
import { SbomScope, sbomDeleteConfirm } from './sbomProfile'

export type SbomDeleteOutcome = 'deleted' | 'cancelled' | 'refused'

export interface SbomDeleteTarget {
    orgUuid: string
    scope: SbomScope
    objectUuid?: string | null
    name: string
    scopeWord: string
    installationType?: string | null
}

export async function deleteSbomProfileConfirmed (client: { mutate: (o: any) => Promise<any> },
    t: SbomDeleteTarget): Promise<SbomDeleteOutcome> {
    const answer: any = await Swal.fire({ ...sbomDeleteConfirm(t.name, t.scope, t.scopeWord, t.installationType),
        icon: 'warning', showCancelButton: true, cancelButtonText: 'Cancel' })
    if (!answer?.isConfirmed) return 'cancelled'
    try {
        await client.mutate({
            mutation: profilesQueries.DeleteSbomProfileGql,
            variables: { org: t.orgUuid, scope: t.scope, object: t.objectUuid ?? null, name: t.name },
        })
        return 'deleted'
    } catch (err: any) {
        console.error(err)
        Swal.fire({ title: 'Error!', text: graphqlRefusalText(err), icon: 'error' })
        return 'refused'
    }
}
