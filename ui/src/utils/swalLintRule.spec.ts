// FDAW-95 (design row 5): the lint rule that keeps text out of SweetAlert2's HTML sinks, run through
// ESLint with the repository's own configuration. The tables below are the rule's contract: what each
// selector refuses, the nearest forms it accepts, and the forms it cannot see (design 3.6).
import { describe, it, expect } from 'vitest'
import { fileURLToPath } from 'url'
import { readdirSync, readFileSync } from 'fs'
import { posix } from 'path'
import { ESLint } from 'eslint'

const UI = fileURLToPath(new URL('../..', import.meta.url))
const eslint = new ESLint({ cwd: UI })
const SOURCES = (readdirSync(UI + 'src', { recursive: true }) as string[])
    .map(f => f.replace(/\\/g, '/'))
    .filter(f => /\.(vue|ts)$/.test(f) && !f.endsWith('.spec.ts'))
    .sort()
// A file's text without its line comments (a comment may name Swal or escapeHtml).
const code = (f: string) => readFileSync(UI + 'src/' + f, 'utf8').replace(/(^|\s)\/\/.*$/gm, '$1')

// The three kinds of source the UI has: a module, a component's plain script block, its setup block.
const VUE = '<template>\n    <div/>\n</template>\n'
const KINDS: Record<string, { path: string, wrap: (code: string) => string }> = {
    'ts': { path: 'src/utils/fdaw95Probe.ts', wrap: c => c + '\n' },
    'vue script': { path: 'src/components/Fdaw95Probe.vue', wrap: c => VUE + '<script lang="ts">\n' + c + '\n</script>\n' },
    'vue script setup': { path: 'src/components/Fdaw95Probe.vue', wrap: c => VUE + '<script setup lang="ts">\n' + c + '\n</script>\n' },
}
const DECL = [
    "import Swal from 'sweetalert2'",
    'const msg = String(Date.now()), name = msg, title = msg, html = msg, ok = msg.length > 3, k = msg',
    'const err = new Error(msg), parts = [msg], options = { text: msg }, helpers = { escapeHtml: (s: string) => s }',
    'const escapeHtml = (s: string) => s, confirmation = (s: string) => ({ text: s }), pending = Promise.resolve(msg)',
    'const other = { fire: (...a: unknown[]) => a, showValidationMessage: (s: string) => s }, notify = (o: object) => o',
    'void name; void title; void html; void ok; void k; void err; void parts; void options; void helpers',
    'void escapeHtml; void confirmation; void pending; void other; void notify',
    '',
].join('\n')

async function messages (code: string, kind: string, template?: string): Promise<string[]> {
    const k = KINDS[kind]
    const text = template ? k.wrap(DECL + code).replace('<div/>', template) : k.wrap(DECL + code)
    const [r] = await eslint.lintText(text, { filePath: UI + k.path })
    return r.messages.map(m => m.ruleId + ': ' + m.message)
}

// Eight written shapes of an expression that is not one string literal.
const NOT_LITERAL = ['msg', 'err.message', 'String(msg)', '`<b>${name}</b>`', '`a constant`', "'Failed: ' + msg", "ok ? 'a constant' : msg", 'msg as string']
const each = (f: (e: string) => string) => NOT_LITERAL.map(f)

const CONTRACT: Array<{ selector: number, message: string, refused: string[], accepted: string[] }> = [
    {
        // 1: a Swal.fire call with two or more arguments, whatever they are.
        selector: 1,
        message: 'Swal.fire takes one options object',
        refused: [
            "Swal.fire('Error!', 'a constant', 'error')",
            "Swal.fire('Error!', 'a constant')",
            "Swal.fire(name, 'a constant', 'error')",
            "Swal.fire({ titleText: 'Error!' }, msg)",
            'Swal.fire(...parts, msg)',
            "Swal?.fire('Error!', msg)",
            ...each(e => "Swal.fire('Error!', " + e + ", 'error')"),
            ...each(e => "Swal.fire('Error!', " + e + ')'),
        ],
        accepted: [
            "Swal.fire({ titleText: 'Error!', text: msg, icon: 'error' })",
            'Swal.fire()',
            "other.fire('Error!', msg, 'error')",
        ],
    },
    {
        // 2: an object written as an argument of Swal.fire whose title, bare or quoted, is not a literal.
        selector: 2,
        message: 'renders title as HTML',
        refused: [
            ...each(e => 'Swal.fire({ title: ' + e + ', text: msg })'),
            ...each(e => "Swal.fire({ 'title': " + e + ', text: msg })'),
            'Swal.fire({ title, text: msg })',
            'Swal.fire({ ...options, title: name })',
            "Swal.fire({ ['title']: name })",
        ],
        accepted: [
            "Swal.fire({ title: 'A constant', text: msg })",
            "Swal.fire({ 'title': 'A constant', text: err.message })",
            ...each(e => 'Swal.fire({ titleText: ' + e + ' })'),
            'Swal.fire({ ...options, titleText: name })',
            "notify({ title: name, content: 'x' })",
            'Swal.fire({ text: msg, didOpen: () => { notify({ title: name }) } })',
        ],
    },
    {
        // 3: the same for html.
        selector: 3,
        message: 'renders html as HTML',
        refused: [
            ...each(e => "Swal.fire({ title: 'x', html: " + e + ' })'),
            ...each(e => "Swal.fire({ title: 'x', 'html': " + e + ' })'),
            "Swal.fire({ title: 'x', html })",
            "Swal.fire({ ...options, html: msg })",
            "Swal.fire({ ['html']: msg })",
        ],
        accepted: [
            "Swal.fire({ title: 'x', html: '<b>constant</b>' })",
            ...each(e => "Swal.fire({ title: 'x', text: " + e + ' })'),
            "// eslint-disable-next-line no-restricted-syntax -- a stated reason\nSwal.fire({ title: 'x', html: String(msg) })",
            'notify({ html: msg })',
            'Swal.fire({ text: msg, didOpen: () => { notify({ html: msg }) } })',
        ],
    },
    {
        // 4: a validation message that is neither a literal nor a call of the bare name escapeHtml.
        selector: 4,
        message: 'renders its argument as HTML',
        refused: [
            ...each(e => 'Swal.showValidationMessage(' + e + ')'),
            'Swal.showValidationMessage(escapeHtml(msg) + msg)',
            'Swal.showValidationMessage(helpers.escapeHtml(msg))',
            'Swal.showValidationMessage(`${escapeHtml(msg)}`)',
            'Swal?.showValidationMessage(msg)',
        ],
        accepted: [
            "Swal.showValidationMessage('A constant')",
            'Swal.showValidationMessage(escapeHtml(msg))',
            'Swal.showValidationMessage(escapeHtml(`Failed: ${msg}`))',
            'Swal.showValidationMessage(\n    escapeHtml(`Failed: ${msg}`)\n)',
            'other.showValidationMessage(msg)',
        ],
    },
    {
        // 5: a single argument that is not an object written in the call.
        selector: 5,
        message: 'A single argument of Swal.fire',
        refused: [
            "Swal.fire('A constant')",
            'Swal.fire(msg)',
            'Swal.fire(options)',
            'Swal.fire(String(msg))',
            'Swal.fire(confirmation(name))',
            'Swal.fire(err.message)',
            'Swal.fire(`Failed: ${msg}`)',
            'Swal.fire(`a constant`)',
            "Swal.fire('Failed: ' + msg)",
            "Swal.fire(ok ? 'a constant' : msg)",
            "Swal.fire(msg || 'Failed')",
            'Swal.fire(msg as string)',
            'Swal.fire(msg!)',
            'Swal.fire(await pending)',
            'Swal.fire(...parts)',
            'Swal.fire([msg])',
            'Swal.fire(new String(msg))',
            'Swal.fire(err?.message)',
            "Swal.fire({ title: name } as object)",
            "Swal.fire(ok ? { title: name } : { titleText: name })",
        ],
        accepted: [
            'Swal.fire({ text: msg })',
            'Swal.fire({})',
            'Swal.fire({ ...options })',
            'Swal.fire({ ...confirmation(name), icon: \'warning\' })',
        ],
    },
]

// What the rule cannot see (design 3.6, threat model residual 1): each is accepted with no message.
const UNSEEN = [
    'Swal.fire({ ...options, ...confirmation(name) })',
    'Swal.fire({ ...{ title: name } })',
    'Swal.fire({ [k]: name })',
    'Swal.fire({ [`title`]: name })',
    'Swal.fire({ footer: msg, confirmButtonText: name, validationMessage: msg, inputPlaceholder: msg })',
    "Swal['fire']('Error!', msg)",
    "(Swal as any).fire('Error!', msg)",
    "(Swal.fire as any)('Error!', msg)",
    "Swal.fire.call(Swal, 'Error!', msg)",
    "const fire = Swal.fire; fire('Error!', msg)",
    'Swal.update({ title: name, html: msg })',
    'Swal.mixin({ title: name })',
]

const cases = (pick: 'refused' | 'accepted') => CONTRACT.flatMap(c => c[pick].map((code): [number, string, string] => [c.selector, code, c.message]))

describe('FDAW-95 the SweetAlert2 lint rule', () => {
    it('(a) every .vue and .ts file under src that is not a spec carries the one rule, and none is ignored', async () => {
        expect(SOURCES.length).toBeGreaterThan(250)
        const expected = JSON.stringify((await eslint.calculateConfigForFile(UI + 'src/utils/teaPublication.ts')).rules['no-restricted-syntax'])
        const rule = JSON.parse(expected)
        expect(rule[0]).toBe(2)
        expect(rule.length).toBe(6)
        const wrong: string[] = []
        for (const f of SOURCES) {
            if (await eslint.isPathIgnored(UI + 'src/' + f)) { wrong.push('ignored: ' + f); continue }
            const got = (await eslint.calculateConfigForFile(UI + 'src/' + f)).rules?.['no-restricted-syntax']
            if (JSON.stringify(got) !== expected) wrong.push('another rule: ' + f)
        }
        expect(wrong).toEqual([])
    })

    it('(a) the selectors are the five of the contract, each with its message', async () => {
        const rule = (await eslint.calculateConfigForFile(UI + 'src/components/ReleaseView.vue')).rules['no-restricted-syntax']
        expect(rule.slice(1).map((o: { message: string }) => CONTRACT.findIndex(c => o.message.includes(c.message)) + 1)).toEqual([1, 2, 3, 4, 5])
    })

    it.each(cases('refused'))('(b) selector %i refuses: %s', async (_n, code, part) => {
        for (const kind of Object.keys(KINDS)) {
            const m = await messages(code, kind)
            expect(m.length, kind + ' ' + JSON.stringify(m)).toBe(1)
            expect(m[0], kind).toContain('no-restricted-syntax: ')
            expect(m[0], kind).toContain(part)
        }
    })

    it.each(cases('accepted'))('(c) next to selector %i, accepted: %s', async (_n, code) => {
        for (const kind of Object.keys(KINDS)) expect(await messages(code, kind), kind).toEqual([])
    })

    it.each(UNSEEN)('(c) not seen by the rule (residual 1), accepted: %s', async (code) => {
        for (const kind of Object.keys(KINDS)) expect(await messages(code, kind), kind).toEqual([])
    })

    it('(c) not seen by the rule (residual 1): a call written in a template', async () => {
        const template = '<button @click="Swal.fire(\'Error!\', msg)">x</button>'
        expect(await messages('', 'vue script setup', template)).toEqual([])
        // the same call in the script block of the same file is refused: the file is linted
        expect(await messages("Swal.fire('Error!', msg)", 'vue script setup', template)).toHaveLength(1)
    })

    it('(d) the names the rule matches: Swal is the library, by one import, with no mixin and no other name', () => {
        const importing = SOURCES.filter(f => /['"]sweetalert2['"]/.test(code(f)))
        expect(importing.length).toBeGreaterThan(30)
        for (const f of importing) {
            // an import of types alone binds no value and is not read
            const text = code(f).replace(/(^|\n)[ \t]*import\s+type\s*\{[^}]*\}\s*from\s*['"]sweetalert2['"]/g, '$1')
            // the library is then named once in the file, in one import of its default as Swal, whatever the layout
            expect(text.match(/['"]sweetalert2['"]/g), f).toHaveLength(1)
            expect(text, f).toMatch(/(^|\n)[ \t]*import\s+Swal\s*(,\s*\{[^}]*\}\s*)?from\s*['"]sweetalert2['"]/)
            expect(text, f).not.toMatch(/Swal\s*\.\s*mixin\b|=\s*Swal\b(?!\s*\??\.)/)
        }
    })

    it('(d) the names the rule matches: escapeHtml is the function of utils/escapeHtml.ts wherever the word is written', () => {
        // Outside its module the word may stand in three places only: one import of it (from the module, or
        // from apiKeyControls.ts, which re-exports the module's), a call of it, and that re-export. Anything
        // else is refused, whatever the layout: a declaration, a parameter, a destructuring, an alias, a
        // member, a second import. Line comments are not read; a block comment is.
        const IMPORT = /import\s*\{([^}]*)\}\s*from\s*['"]([^'"]*)['"]/g
        const using: string[] = []
        for (const f of SOURCES) {
            if (f === 'utils/escapeHtml.ts' || !/\bescapeHtml\b/.test(code(f))) continue
            using.push(f)
            const sources: string[] = []
            let rest = code(f).replace(IMPORT, (all: string, names: string, from: string) => {
                if (!names.split(',').some(n => n.trim() === 'escapeHtml')) return all
                sources.push(from)
                return ''
            })
            expect(sources, f).toHaveLength(1)
            // the module the import names, as a path under src: `@/` is src, a relative path starts at the file's folder
            const from = sources[0].startsWith('@/') ? sources[0].slice(2) : posix.join(posix.dirname(f), sources[0])
            expect(f === 'utils/apiKeyControls.ts' ? ['utils/escapeHtml'] : ['utils/escapeHtml', 'utils/apiKeyControls'], f + ' imports it from ' + sources[0]).toContain(from)
            if (f === 'utils/apiKeyControls.ts') rest = rest.replace(/(^|\n)export\s*\{\s*escapeHtml\s*\}/, '$1')
            rest = rest.replace(/(?<![.\w$])(?<!function\s*\*?\s*)escapeHtml[ \t]*\(/g, '(')
            expect(rest.split('\n').filter(l => /\bescapeHtml\b/.test(l)), f).toEqual([])
        }
        expect(using).toEqual(expect.arrayContaining(['components/AppWrapper.vue', 'components/ExternalKeysPanel.vue', 'utils/apiKeyControls.ts']))
        expect(readFileSync(UI + 'src/utils/escapeHtml.ts', 'utf8')).toMatch(/^export function escapeHtml \(s: string\): string \{$/m)
    })
})
