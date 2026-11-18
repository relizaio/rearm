// @vitest-environment happy-dom
//
// FDAW-122 row 2 (FDAW-11 threat model item 7): only a document that starts as the generator starts
// one is printed; it reaches one sandboxed frame's srcdoc and nothing else; the sandbox never has
// allow-scripts and is set before the content and before the frame is in the page.
// happy-dom runs no script of a frame whatever its sandbox, so no cell here shows a browser refusing a
// script: the cells hold the attributes and the sinks. The fixtures are string literals with \n, never files.
import { afterEach, describe, expect, it, vi } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import { join, resolve } from 'node:path'
import * as DP from './documentPrint'
import { expectSwept } from './__fixtures__/fdaSweep'

const enc = (s: string) => new TextEncoder().encode(s)
// HtmlDocuments.document of rearm-saas (service/saas/HtmlDocuments.java:58-72), with a body.
const doc = (title: string, body: string) => '<!doctype html>\n<html lang="en">\n<head>\n<meta charset="utf-8">\n'
    + '<meta http-equiv="Content-Security-Policy" content="default-src \'none\'; style-src \'unsafe-inline\'">\n'
    + '<title>' + title + '</title>\n<style>\nbody { font-family: sans-serif; }\n</style>\n</head>\n<body>\n' + body + '</body>\n</html>\n'
const GOOD = doc('Device software support statement', '<h1>Device software support statement</h1>\n<p class="prose">Caf\u00e9 &lt;script&gt;</p>\n')
// What a generator with a missed escape would write: the fixed start, then markup from stored text.
const HOSTILE = doc('t', '<script>parent.__hit = 1; top.__hit = 1; window.__hit = 1</script>\n<img src="x" onerror="parent.__hit = 2">\n'
    + '<meta http-equiv="refresh" content="0;url=https://example.invalid/">\n<a href="javascript:parent.__hit=3" id="hostile-link">x</a>\n'
    + '<form action="/api/x" method="post"><input autofocus name="hostile-input"></form>\n<p>DRAFT REQUIRED plain words</p>\n')

async function loaded (frame: HTMLIFrameElement) {
    await new Promise(r => setTimeout(r, 50))
    return frame
}

describe('documentPrint (FDAW-122)', () => {
    afterEach(() => {
        DP.removePrintFrame()
        delete (window as any).__hit
        vi.restoreAllMocks()
    })

    it('(a) the start of a generated document, as rearm-saas DraftMark.HEAD_OPEN has it', () => {
        expect(DP.GENERATED_HEAD_OPEN).toBe('<!doctype html>\n<html lang="en">\n<head>\n<meta charset="utf-8">\n<meta http-equiv="Content-Security-Policy" content="default-src \'none\'; style-src \'unsafe-inline\'">\n<title>')
        expect(DP.GENERATED_HEAD_OPEN).toHaveLength(170)
        expect(DP.PRINTABLE_PARTS).toEqual(['DEVICE_SUPPORT_STATEMENT', 'VULNERABILITY_ASSESSMENT'])
        expect(DP.PRINT_BUTTON).toBe('Print or save as PDF')
        expectSwept([DP.PRINT_BUTTON, DP.NOT_PRINTABLE, DP.PRINT_FAILED].join('\n'))
    })

    it('(b) a document is printable only when it is UTF-8 without a byte order mark and starts as the generator starts one', () => {
        expect(DP.printableHtml(enc(GOOD))).toBe(GOOD)
        expect(DP.printableHtml(enc(GOOD).buffer as ArrayBuffer)).toBe(GOOD)
        expect(DP.printableHtml(enc(HOSTILE))).toBe(HOSTILE)
        const bytes = (prefix: number[], s: string) => new Uint8Array([...prefix, ...enc(s)])
        const refused: [string, Uint8Array | null | undefined][] = [
            ['byte order mark', bytes([0xef, 0xbb, 0xbf], GOOD)],
            ['one invalid byte in the body', new Uint8Array([...enc(GOOD.slice(0, 300)), 0xff, ...enc(GOOD.slice(300))])],
            ['latin-1 e acute', new Uint8Array([...enc(GOOD.slice(0, 300)), 0xe9, ...enc(GOOD.slice(300))])],
            ['CR LF line ends', enc(GOOD.replace(/\n/g, '\r\n'))],
            ['a leading space', enc(' ' + GOOD)],
            ['a leading line feed', enc('\n' + GOOD)],
            ['a leading comment', enc('<!-- x -->' + GOOD)],
            ['upper case doctype', enc(GOOD.replace('<!doctype html>', '<!DOCTYPE html>'))],
            ['no policy meta', enc(GOOD.replace(/<meta http-equiv[^\n]*\n/, ''))],
            ['a wider policy', enc(GOOD.replace("default-src 'none'", "default-src 'none'; script-src 'unsafe-inline'"))],
            ['the policy after the title', enc(GOOD.replace(/(<meta http-equiv[^\n]*\n)(<title>[^\n]*\n)/, '$2$1'))],
            ['an uploaded page', enc('<html><body><script>parent.__hit = 1</script></body></html>')],
            ['the start as text in a body', enc('<html><body>' + DP.GENERATED_HEAD_OPEN + '</body></html>')],
            ['json', enc('{"a":1}')],
            ['empty', new Uint8Array(0)],
            ['null', null],
            ['undefined', undefined],
        ]
        for (const [label, b] of refused) expect(DP.printableHtml(b), label).toBeNull()
    })

    it('(c) the frame: sandbox without allow-scripts, set before the content and before the frame is in the page', async () => {
        const seen: any[] = []
        const append = vi.spyOn(document.body, 'appendChild')
        append.mockImplementation(function (this: any, node: any) {
            seen.push({ tag: node.tagName, sandbox: node.getAttribute('sandbox'), srcdoc: node.getAttribute('srcdoc'), connected: node.isConnected })
            return Node.prototype.appendChild.call(this, node)
        } as any)
        const frame = DP.printHtml(GOOD)
        expect(seen).toEqual([{ tag: 'IFRAME', sandbox: 'allow-same-origin allow-modals', srcdoc: GOOD, connected: false }])
        expect(DP.PRINT_SANDBOX.split(' ').sort()).toEqual(['allow-modals', 'allow-same-origin'])
        expect(frame.getAttribute('sandbox')!.split(/\s+/)).not.toContain('allow-scripts')
        const attrs = Object.fromEntries(frame.getAttributeNames().map(n => [n, frame.getAttribute(n)]))
        expect(attrs).toEqual({ sandbox: 'allow-same-origin allow-modals', id: 'rearm-document-print-frame', 'aria-hidden': 'true', tabindex: '-1',
            referrerpolicy: 'no-referrer', style: 'position: fixed; right: 0; bottom: 0; width: 0; height: 0; border: 0; visibility: hidden;',
            srcdoc: GOOD })
        expect(frame.getAttribute('src')).toBeNull()
        expect(frame.parentElement).toBe(document.body)
    })

    it('(d) print is asked of the frame once it has loaded; one frame at a time; a browser without a dialog is said so', async () => {
        const print = vi.fn()
        const focus = vi.fn()
        const first = DP.printHtml(GOOD)
        ;(first.contentWindow as any).print = print
        ;(first.contentWindow as any).focus = focus
        expect(print).not.toHaveBeenCalled()
        await loaded(first)
        expect(print).toHaveBeenCalledTimes(1)
        expect(focus).toHaveBeenCalledTimes(1)
        // not before the load: a stub put on the frame window the moment the frame enters the page sees no call until then
        const early = vi.fn()
        const append = vi.spyOn(document.body, 'appendChild').mockImplementationOnce((node: any) => {
            const r = Node.prototype.appendChild.call(document.body, node)
            node.contentWindow.print = early
            return r
        })
        const second = DP.printHtml(GOOD)
        append.mockRestore()
        expect(early).not.toHaveBeenCalled()
        await loaded(second)
        expect(early).toHaveBeenCalledTimes(1)
        expect(first.isConnected).toBe(false)
        expect(document.querySelectorAll('iframe')).toHaveLength(1)
        // happy-dom's frame window has no print: the load handler's call throws, the frame goes, the caller is told
        const failed = vi.fn()
        vi.spyOn(console, 'error').mockImplementation(() => {})
        const third = DP.printHtml(GOOD, failed)
        expect(second.isConnected).toBe(false)
        await loaded(third)
        expect(failed).toHaveBeenCalledTimes(1)
        expect(third.isConnected).toBe(false)
        expect(document.querySelectorAll('iframe')).toHaveLength(0)
        DP.removePrintFrame()
    })

    it('(e) hostile markup after the fixed start reaches the frame srcdoc and nothing of the page', async () => {
        const before = document.body.children.length
        const frame = DP.printHtml(DP.printableHtml(enc(HOSTILE))!)
        ;(frame.contentWindow as any).print = vi.fn()
        await loaded(frame)
        expect(document.body.children.length).toBe(before + 1)
        for (const sel of ['script', 'img', 'form', 'input', 'a#hostile-link', 'meta[http-equiv="refresh"]', '[onerror]']) {
            expect(document.querySelectorAll(sel), sel).toHaveLength(0)
        }
        expect(document.title).not.toBe('t')
        expect((window as any).__hit).toBeUndefined()
        // the page's own markup holds the document once, as the escaped value of the one attribute
        const outer = document.body.innerHTML
        expect(outer.split('parent.__hit = 1').length - 1).toBe(1)
        expect(frame.getAttribute('srcdoc')).toBe(HOSTILE)
        expect(frame.getAttribute('sandbox')).toBe('allow-same-origin allow-modals')
    })

    it('(f) source: srcdoc and sandbox are in documentPrint.ts alone, and no changed file has an HTML sink', () => {
        const SRC = resolve(__dirname, '..')
        const files = (readdirSync(SRC, { recursive: true, encoding: 'utf8' }) as string[])
            .filter(f => /\.(vue|ts)$/.test(f) && !/\.spec\.ts$/.test(f) && !f.includes('__fixtures__'))
        expect(files.length).toBeGreaterThan(200)
        const read = (f: string) => readFileSync(join(SRC, f), 'utf8')
        const holding = (re: RegExp) => files.filter(f => re.test(read(f))).map(f => f.replace(/\\/g, '/')).sort()
        expect(holding(/srcdoc/i)).toEqual(['utils/documentPrint.ts'])
        expect(holding(/allow-same-origin|allow-modals|allow-scripts/)).toEqual(['utils/documentPrint.ts'])
        expect(holding(/<iframe|createElement\(\s*['"]iframe/i)).toEqual(['utils/documentPrint.ts'])
        // allow-scripts is named in comments only: no string of the file holds it
        const own = read('utils/documentPrint.ts')
        expect(own.split('\n').filter(l => l.includes('allow-scripts')).every(l => /^\s*(\/\/|\*|\/\*\*)/.test(l))).toBe(true)
        expect(own.match(/setAttribute\('sandbox', PRINT_SANDBOX\)/g)).toHaveLength(1)
        for (const f of ['utils/documentPrint.ts', 'utils/packageDownloads.ts', 'utils/downloadLog.ts', 'components/ReleaseSubmissionsPanel.vue',
            'components/SubmissionDraftCard.vue']) {
            const s = read(f)
            expect(s, f).not.toMatch(/v-html/)
            expect(s, f).not.toMatch(/innerHTML|outerHTML|insertAdjacentHTML|document\.write/)
            expect(s, f).not.toMatch(/\bhtml\s*:/)
        }
    })
})
