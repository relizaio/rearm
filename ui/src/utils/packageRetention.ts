// The retention guard's refusal (FDAW-12 3.7): an archive of a product, a component or an
// organization that holds package versions without a recorded zip download is refused with
// extensions.code PACKAGE_EXPORT_REQUIRED. Decided by the code, never by searching the text.

import { graphqlErrorEntries } from './graphqlErrorText'

export const PACKAGE_EXPORT_REQUIRED = 'PACKAGE_EXPORT_REQUIRED'

/**
 * The server's sentence of a retention refusal: the message of the first error entry whose
 * extensions.code is PACKAGE_EXPORT_REQUIRED and whose message is a non-empty string; else null.
 * The caller shows it as text, never as markup.
 */
export function retentionRefusal (err: unknown): string | null {
    const entry = graphqlErrorEntries(err).find((x: any) => x?.extensions?.code === PACKAGE_EXPORT_REQUIRED
        && typeof x?.message === 'string' && x.message !== '')
    return entry ? entry.message : null
}
