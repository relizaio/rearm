// Rendering for SUPPORT_WINDOW release update events.

/**
 * Render the window value a SUPPORT_WINDOW event carries.
 *
 * The input shape is produced by ReleaseData.supportWindowValueString, which builds
 * "eos=" + eos + ", eol=" + eol -- string concatenation over two possibly-null values, so
 * an undeclared date arrives as the literal text "null" rather than an absent segment.
 * Rendering that raw would put "eos=null, eol=2033-03-31" in front of an operator.
 *
 * Coupled to that server method by nothing but this comment and the spec beside it, so the
 * unrecognised-input case deliberately PASSES THROUGH unchanged: if the server format ever
 * changes, the history shows something odd rather than nothing at all.
 */
export function formatSupportWindow (value: string | null | undefined): string {
    if (!value) return 'not declared'
    const m = /^eos=(.*), eol=(.*)$/.exec(value)
    if (!m) return value
    const part = (v: string) => (v === 'null' ? 'not declared' : v)
    return `EOS ${part(m[1])}, EOL ${part(m[2])}`
}
