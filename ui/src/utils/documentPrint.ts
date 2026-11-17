// Print or save as PDF of an HTML document ReARM generated (task FDAW-122; FDAW-11 threat model item 7). The
// document never enters the page: it is given, as a string, to the srcdoc of one iframe whose sandbox has no
// allow-scripts, so no script, event handler or javascript: URL of it runs, and no form, popup, top
// navigation or meta refresh works. The sandbox has allow-same-origin and allow-modals for one reason: the
// page calls print() on the frame's window, which a page cannot do on a window of another origin and which a
// sandbox without allow-modals ignores. Only a document that starts, byte for byte, as the generator starts
// every document is printed: that start holds the Content-Security-Policy meta that refuses every load.
// THE ONLY FILE OF THE UI THAT SETS srcdoc, AND IT NEVER SETS allow-scripts.

/**
 * How HtmlDocuments.document (rearm-saas) starts every document, up to and with the title's opening tag:
 * DraftMark.HEAD_OPEN there. Constant text before any stored value.
 */
export const GENERATED_HEAD_OPEN = '<!doctype html>\n<html lang="en">\n<head>\n<meta charset="utf-8">\n'
    + '<meta http-equiv="Content-Security-Policy" content="default-src \'none\'; style-src \'unsafe-inline\'">\n<title>'

/** The sandbox of the print frame. Never allow-scripts. */
export const PRINT_SANDBOX = 'allow-same-origin allow-modals'

/** The parts whose stored file is an HTML document of the generator (PackagePart). */
export const PRINTABLE_PARTS: string[] = ['DEVICE_SUPPORT_STATEMENT']

export const PRINT_BUTTON = 'Print or save as PDF'

export const PRINT_FRAME_ID = 'rearm-document-print-frame'

export const NOT_PRINTABLE = 'This file is not a document in the form ReARM generates, so this page does not print it. Download the file and open it instead.'
export const PRINT_FAILED = 'The browser did not open its print dialog. Download the file, open it and print it from the browser.'

/** The document as text when it is UTF-8 without a byte order mark and starts as the generator starts one; else null. */
export function printableHtml (bytes: ArrayBuffer | Uint8Array | null | undefined): string | null {
    if (!bytes) return null
    let text: string
    try {
        text = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(bytes)
    } catch {
        return null
    }
    return text.startsWith(GENERATED_HEAD_OPEN) ? text : null
}

/** Removes the print frame, if one is there. */
export function removePrintFrame (): void {
    document.getElementById(PRINT_FRAME_ID)?.remove()
}

/**
 * Loads `markup` into a new hidden sandboxed frame and asks the browser to print that frame's document. One
 * frame at a time: the one before is removed first. The sandbox is set before the content and before the
 * frame is in the page, because a sandbox counts only for what is loaded after it is set. When the browser
 * gives no print dialog the frame is removed and `onFailure` is called.
 */
export function printHtml (markup: string, onFailure: () => void = () => {}): HTMLIFrameElement {
    removePrintFrame()
    const frame = document.createElement('iframe')
    frame.setAttribute('sandbox', PRINT_SANDBOX)
    frame.setAttribute('id', PRINT_FRAME_ID)
    frame.setAttribute('aria-hidden', 'true')
    frame.setAttribute('tabindex', '-1')
    frame.setAttribute('referrerpolicy', 'no-referrer')
    frame.setAttribute('style', 'position: fixed; right: 0; bottom: 0; width: 0; height: 0; border: 0; visibility: hidden;')
    frame.addEventListener('load', () => {
        try {
            const w: any = frame.contentWindow
            w.focus()
            w.print()
        } catch (err: any) {
            console.error(err)
            frame.remove()
            onFailure()
        }
    }, { once: true })
    frame.srcdoc = markup
    document.body.appendChild(frame)
    return frame
}
