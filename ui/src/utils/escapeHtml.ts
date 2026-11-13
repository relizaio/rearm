// Text made safe to sit in a SweetAlert2 HTML sink that has no text form (an html string, a select
// option label, a validation message). Its own module, with no imports, so any file can use it.
export function escapeHtml (s: string): string {
    return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}
