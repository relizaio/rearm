// Cells of regulated-outputs.mjs (FDAW-127). Run from documentation_site: node --test ci/regulated-outputs/regulated-outputs.test.mjs
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, mkdtempSync, mkdirSync, writeFileSync, copyFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, dirname } from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import {
    HEADING, URL, RECORD, PAGES, NOT_COMPARED, NO_ENTRY, NOT_THE_FORM, TAG_NOT_HERE, TAG_NOT_MADE, HOW_TO_MEND, USAGE, sha256, parseRecord,
    expectedSection, hiddenLines, bodyFailures, pageFailures, isProductTag, previousTag, checkRelease, isReadableRef, isRevision,
    sectionArguments, PINS, DOCKERFILE, RELEASE_ZIP, PIN_MOVED, HOW_TO_MEND_PINS, dockerInstructions, rebomPins, pinFailures, rebomPinsAt
} from './regulated-outputs.mjs'

const E1 = { title: 'First record', action: 'Validate from here.' }
const E2 = { title: 'Readiness rules: rule set 2', action: 'Run your validation of the gate again.' }
const E3 = { title: 'Package zip: a new file', action: 'Read the README.' }

function entryLines(e, n) {
    return ['### RO-' + n + ': ' + e.title, '', '- Outputs: Package zip', '- Kind of change: names and layout',
        '- What changed: ' + (e.what || 'w'), '- What to do: ' + e.action, '- Identifiers: `k1` A -> B', '']
}

const K1_ROW = '| `k1` | B | one | computed: by a test |'

// A record in the generator's form; `edit` changes the lines before they are joined.
function record(entries, edit) {
    let lines = ['# Regulated output changes (ReARM Pro), record at RO-' + entries.length, '', '<!-- Generated. -->', '',
        'Words.', '', '## Outputs', '', '| Output | What it is | Identifiers | Not held by an identifier |', '|---|---|---|---|',
        '| Package zip | x | `k1` | y |', '', '## Identifiers', '', '| Identifier | Value | Names | Held |',
        '|---|---|---|---|', K1_ROW, '| `readiness-rules` | ' + sha256('page') + ' | two | computed: by a test |',
        '| `gone` | (not held) | three | raised: by hand |', '| `d1` | x1 | four | declared: by its author |', '', '## Changes', '',
        'The newest entry is the last.', '']
    entries.forEach((e, i) => { lines = lines.concat(entryLines(e, i + 1)) })
    if (edit) lines = edit(lines) || lines
    return lines.join('\n')
}

// ---- fixtures of rebom: its build files in the two forms it has had (the source pin since FDAW-111, the release pin before)
const C40 = 'e'.repeat(40)
const GO = 'a'.repeat(64)
const NODE = 'b'.repeat(64)
const SUM_X64 = '1a'.repeat(32)
const SUM_ARM = '2b'.repeat(32)
const dockerSource = (pinLine = 'ARG REARM_CLI_COMMIT=' + C40,
    from = 'FROM --platform=$BUILDPLATFORM golang:1.26.10-alpine3.24@sha256:' + GO + ' AS rearm-cli') => [
    '# rebom runs rearm-cli built from source at REARM_CLI_COMMIT. To move it: change both ARGs.',
    '#   ARG REARM_CLI_COMMIT=' + 'f'.repeat(40) + ' was the pin before: a comment names another commit',
    from,
    'ARG TARGETOS',
    'ARG TARGETARCH',
    pinLine,
    'ARG REARM_CLI_VERSION=26.10.17-11-geeeeeeee',
    'RUN apk add --no-cache git && \\',
    '    echo "$REARM_CLI_COMMIT" | grep -Eqx \'[0-9a-f]{40}\' && \\',
    '    go build -o /out/rearm .',
    '',
    'FROM node:26.12.1-alpine3.24@sha256:' + NODE + ' AS builder',
    'ARG TARGETARCH',
    'COPY --from=rearm-cli /out/rearm /usr/local/bin/rearm',
    'COPY tools.${TARGETARCH}.sha256 /tmp/tools.sha256',
    'RUN wget https://github.com/CycloneDX/cyclonedx-cli/releases/download/v0.32.0/cyclonedx-linux-musl-x64 && \\',
    '    sha256sum -c /tmp/tools.sha256 && \\',
    '    chmod +x /usr/local/bin/cyclonedx-cli',
    ''].join('\n')
const toolsOf = (x64 = SUM_X64, arm = SUM_ARM) => ({ amd64: x64 + '  /usr/local/bin/cyclonedx-cli\n', arm64: arm + '  /usr/local/bin/cyclonedx-cli\n' })
const GOOD = { dockerfile: dockerSource(), tools: toolsOf() }
const TOOLS_SUM = sha256('amd64 ' + SUM_X64 + ' /usr/local/bin/cyclonedx-cli\narm64 ' + SUM_ARM + ' /usr/local/bin/cyclonedx-cli\n')
const GOOD_PINS = { 'rebom-engine': C40, 'rebom-engine-image': 'sha256:' + GO, 'rebom-tools': TOOLS_SUM }
const PIN_ROWS = ['rebom-engine', 'rebom-engine-image', 'rebom-tools'].map(k => '| `' + k + '` | ' + GOOD_PINS[k] + ' | a pin | declared: by its author |')
// A record that names the pins of GOOD, as every record from part 1 on does.
const pinned = (entries, edit) => record(entries, l => {
    l.splice(l.indexOf('## Changes') - 1, 0, ...PIN_ROWS)
    return edit ? (edit(l) || l) : l
})
const dockerRelease = (version = '26.11.9') => [
    'FROM node:26.12.1-alpine3.24@sha256:' + NODE + ' AS builder',
    'ARG TARGETARCH',
    '# The rearm CLI release rebom runs.',
    'ARG REARM_CLI_VERSION=' + version,
    'COPY tools.${TARGETARCH}.sha256 /tmp/tools.sha256',
    'RUN wget https://cdn.example/rearm-download/${REARM_CLI_VERSION}/rearm-${REARM_CLI_VERSION}-linux-${TARGETARCH}.zip && \\',
    '    unzip rearm-${REARM_CLI_VERSION}-linux-${TARGETARCH}.zip && \\',
    '    sha256sum -c /tmp/tools.sha256',
    ''].join('\n')
const releaseTools = (version = '26.11.9') => ({
    amd64: SUM_X64 + '  /usr/local/bin/cyclonedx-cli\n' + '3'.repeat(64) + '  rearm-' + version + '-linux-amd64.zip\n',
    arm64: SUM_ARM + '  /usr/local/bin/cyclonedx-cli\n' + '4'.repeat(64) + '  rearm-' + version + '-linux-arm64.zip\n'
})
const none = n => [HEADING, '', NO_ENTRY(n)].join('\n')

test('a the page of this documentation is a record the check reads', () => {
    const real = parseRecord(readFileSync(RECORD, 'utf8'))
    assert.equal(real.entries.length, real.last)
    assert.equal(real.entries[0].title, 'First record')
    // every row of the identifier table is read: counted here without the module's reader, so a row another task adds
    // counts too (round 5; round 4 asserted 25)
    const lines = readFileSync(RECORD, 'utf8').split('\n')
    const rows = lines.slice(lines.indexOf('## Identifiers'), lines.indexOf('## Changes')).filter(l => l.startsWith('| `'))
    assert.ok(rows.length > 0)
    assert.equal(real.keys.size, rows.length)
    for (const key of PINS) assert.equal(real.held.get(key), 'declared', key)
    assert.match(real.keys.get('rebom-engine'), /^([0-9a-f]{40}|[0-9]{2}\.[0-9]{2}\.[0-9]+)$/)
    assert.deepEqual([...new Set(real.held.values())].sort(), ['computed', 'declared', 'raised'])
    assert.equal(real.held.get('fda-checker-rules'), 'declared')
    assert.equal(real.held.get('merge-version'), 'raised')
    assert.equal(real.held.get('manifest-form'), 'computed')
    for (const key of Object.keys(PAGES)) assert.match(real.keys.get(key), /^[0-9a-f]{64}$/)
    assert.equal(RECORD, 'docs/workflows/regulated-output-changes.md')
})

test('b a record in the generated form is read whole', () => {
    const r = parseRecord(record([E1, E2]))
    assert.equal(r.last, 2)
    assert.deepEqual([...r.keys], [['k1', 'B'], ['readiness-rules', sha256('page')], ['gone', '(not held)'], ['d1', 'x1']])
    assert.deepEqual([...r.held], [['k1', 'computed'], ['readiness-rules', 'computed'], ['gone', 'raised'], ['d1', 'declared']])
    assert.deepEqual(r.entries.map(e => [e.number, e.title, e.action]), [[1, E1.title, E1.action], [2, E2.title, E2.action]])
    assert.equal(r.entries[0].block, entryLines(E1, 1).join('\n'))
    // the last entry reads as it will when another follows it: one empty line after its last line
    assert.equal(r.entries[1].block, entryLines(E2, 2).join('\n'))
})

test('c a file that is not the generated form is refused', () => {
    const bad = (edit, message, entries = [E1, E2]) => assert.throws(() => parseRecord(typeof edit === 'string' ? edit : record(entries, edit)),
        { message }, String(edit))
    const notGenerated = 'the change record is not the generated file: it has a carriage return or no line feed at its end'
    bad(record([E1]).replace(/\n/g, '\r\n'), notGenerated)
    bad(record([E1]).replace('Words.\n', 'Words.\r\n'), notGenerated)
    bad(record([E1]).slice(0, -1), notGenerated)
    bad('', notGenerated)
    assert.throws(() => parseRecord(null), { message: notGenerated })
    const title = 'the change record does not start with its title line'
    for (const t of ['# regulated output changes (ReARM Pro), record at RO-2', '# Regulated output changes (ReARM Pro), record at RO-02',
        '# Regulated output changes (ReARM Pro), record at RO-2 ', '## Regulated output changes (ReARM Pro), record at RO-2',
        '# Regulated output changes (ReARM CE), record at RO-2', '# Regulated output changes (ReARM Pro), record at RO-0',
        'x # Regulated output changes (ReARM Pro), record at RO-2', '']) {
        bad(l => { l[0] = t }, title)
    }
    bad(l => ['', ...l], title)
    const sections = 'the change record does not have its two sections once each, in order'
    bad(l => l.filter(x => x !== '## Changes'), sections)
    bad(l => l.filter(x => x !== '## Identifiers'), sections)
    bad(l => [...l.slice(0, -1), '## Changes', ''], sections)
    bad(l => { l.splice(l.indexOf('## Changes'), 0, '## Identifiers') }, sections)
    bad(l => { l[l.indexOf('## Identifiers')] = '## Identifiers in this release' }, sections)
    bad(l => { const i = l.indexOf('## Identifiers'); const j = l.indexOf('## Changes'); l[i] = '## Changes'; l[j] = '## Identifiers' }, sections)
    const tail = ' | one | computed: by a test |'
    for (const row of ['| `k1` | B C' + tail, '| `k1` | ' + tail, '| `K1` | B' + tail, '| `k1` | B | one | computed: by a test',
        '| `k1` | B|', '| `k1` | (Not held)' + tail, '| `k1` | ' + 'x'.repeat(81) + tail, '| `k1` | B | one | held |',
        '| `k1` | B | one | Computed: by a test |', '| `k1` | B | one | computed by a test |', '| `k1` | B | one | computed:  |',
        '| `k1` | B | one | declared |', '| `k1` | B | one | other: by a test |', '| `k1` | B | computed: by a test |']) {
        bad(l => { l[l.indexOf(K1_ROW)] = row }, 'the change record has an identifier row this check cannot read: ' + row)
    }
    bad(l => { l.splice(l.indexOf(K1_ROW), 0, '| `k1` | C' + tail) }, 'the change record lists k1 twice')
    for (const h of ['## Other', '### RO-2 Readiness', '### RO-x: Readiness', '# Top', '#### RO-2: Deeper', '### RO-02: Padded', '### RO-2:']) {
        bad(l => { l[l.indexOf('### RO-2: ' + E2.title)] = h }, 'the change record has a heading that is no entry: ' + h)
    }
    bad(l => { l[l.indexOf('### RO-2: ' + E2.title)] = '### RO-3: ' + E2.title }, 'entry 2 of the change record is numbered RO-3')
    bad(l => { l[l.indexOf('### RO-1: ' + E1.title)] = '### RO-2: ' + E1.title }, 'entry 1 of the change record is numbered RO-2')
    bad(l => { l[l.indexOf('### RO-2: ' + E2.title)] = '### RO-1: ' + E2.title }, 'entry 2 of the change record is numbered RO-1')
    bad(l => l.filter(x => x !== '- What to do: ' + E2.action), 'RO-2 does not say what to do exactly once')
    bad(l => [...l.slice(0, -1), '- What to do: more', ''], 'RO-2 does not say what to do exactly once')
    bad(l => { l[l.indexOf('- What to do: ' + E1.action)] = '- what to do: ' + E1.action }, 'RO-1 does not say what to do exactly once')
    bad(l => { l[l.indexOf('- What to do: ' + E1.action)] = '- What to do: ' }, 'RO-1 does not say what to do exactly once')
    bad(l => { l[0] = l[0].replace('RO-2', 'RO-3') }, 'the change record says it stands at RO-3 and holds 2 entries')
    bad(l => { l[0] = l[0].replace('RO-2', 'RO-1') }, 'the change record says it stands at RO-1 and holds 2 entries')
    bad(l => { l[0] = l[0].replace('RO-0', 'RO-1') }, 'the change record says it stands at RO-1 and holds 0 entries', [])
})

test('d the section names the entries a release adds, and only those', () => {
    const one = parseRecord(record([E1]))
    const two = parseRecord(record([E1, E2]))
    const three = parseRecord(record([E1, E2, E3]))
    assert.equal(expectedSection(one, one), none(1))
    assert.equal(expectedSection(three, three), none(3))
    assert.equal(expectedSection(one, two), [HEADING, '', 'ReARM Pro, change record entry RO-2 (' + URL + '):', '',
        '- RO-2: ' + E2.title + '. What to do: ' + E2.action].join('\n'))
    assert.equal(expectedSection(one, three), [HEADING, '', 'ReARM Pro, change record entries RO-2 to RO-3 (' + URL + '):', '',
        '- RO-2: ' + E2.title + '. What to do: ' + E2.action, '- RO-3: ' + E3.title + '. What to do: ' + E3.action].join('\n'))
    assert.equal(expectedSection(null, two), [HEADING, '', 'ReARM Pro, change record entries RO-1 to RO-2 (' + URL + '):', '',
        '- RO-1: ' + E1.title + '. What to do: ' + E1.action, '- RO-2: ' + E2.title + '. What to do: ' + E2.action].join('\n'))
    assert.equal(expectedSection(null, one), [HEADING, '', 'ReARM Pro, change record entry RO-1 (' + URL + '):', '',
        '- RO-1: ' + E1.title + '. What to do: ' + E1.action].join('\n'))
    assert.equal(HEADING, '## Regulated output changes')
    // the line of a release without an entry says that no entry was added, and that this is not "no change"
    assert.equal(NO_ENTRY(2), 'ReARM Pro: no new entry in the change record in this release; it stands at RO-2 (' + URL
        + '). This does not say that no output changed: the page lists what the record does not hold.')
    assert.equal(expectedSection(two, two).split('\n')[2], NO_ENTRY(2))
    assert.ok(!/\bnone\b|no change|nothing changed|unchanged/i.test(NO_ENTRY(2)))
    assert.equal(URL, 'https://docs.rearmhq.com/workflows/regulated-output-changes.html')
})

test('e an entry a release carried is never changed or dropped', () => {
    const two = parseRecord(record([E1, E2]))
    const changed = (edit, n) => assert.throws(() => expectedSection(two, parseRecord(record([E1, E2, E3], edit))),
        { message: 'RO-' + n + ' is not the entry the release before carried under that number: it was edited after that release, or this commit is on an older line that numbered an entry of its own. A release carries every entry of the release before it, unchanged' })
    changed(l => { l[l.indexOf('### RO-1: ' + E1.title)] = '### RO-1: First Record' }, 1)
    changed(l => { l[l.indexOf('- What to do: ' + E2.action)] = '- What to do: Nothing.' }, 2)
    changed(l => { l[l.indexOf('- What changed: w')] = '- What changed: W' }, 1)
    changed(l => { l[l.lastIndexOf('- Identifiers: `k1` A -> B', l.indexOf('### RO-3: ' + E3.title))] = '- Identifiers: `k1` A -> C' }, 2)
    changed(l => { l.splice(l.indexOf('### RO-2: ' + E2.title) - 1, 0, '- Note: added later') }, 1)
    changed(l => { l.splice(l.indexOf('### RO-3: ' + E3.title) - 1, 0, '- Note: added later') }, 2)
    changed(l => { l.splice(l.indexOf('- Kind of change: names and layout'), 1) }, 1)
    assert.throws(() => expectedSection(two, parseRecord(record([E1]))), { message: 'RO-2 was in the release before and is not in this record: it was taken out after that release, or this commit is on an older line than that release. A release carries every entry of the release before it, unchanged' })
    assert.throws(() => expectedSection(two, parseRecord(record([E2, E1]))), { message: 'RO-1 is not the entry the release before carried under that number: it was edited after that release, or this commit is on an older line that numbered an entry of its own. A release carries every entry of the release before it, unchanged' })
    // the last entry of the release before is held too, although nothing followed it there
    assert.equal(expectedSection(two, parseRecord(record([E1, E2, E3]))).split('\n').length, 5)
})

test('f lines a reader does not see', () => {
    const h = text => hiddenLines(text.split('\n')).map(x => x ? 'h' : '.').join('')
    assert.equal(h('a\n```\nb\n```\nc'), '.hhh.')
    assert.equal(h('a\n~~~\nb\n~~~\nc'), '.hhh.')
    assert.equal(h('```text\nb\n```\nc'), 'hhh.')
    assert.equal(h('```\nb\n~~~\nc\n```\nd'), 'hhhhh.')
    assert.equal(h('````\nb\n```\nc\n````\nd'), 'hhhhh.')
    assert.equal(h('```\nb\n`````\nd'), 'hhh.')
    assert.equal(h('   ```\nb\n   ```\nd'), 'hhh.')
    assert.equal(h('    ```\nb'), '..')
    assert.equal(h('```\nb\nc'), 'hhh')
    assert.equal(h('``\nb'), '..')
    assert.equal(h('a ```\nb'), '..')
    assert.equal(h('a\n<!--\nb\n-->\nc'), '.hhh.')
    assert.equal(h('a <!-- x -->\nb'), 'h.')
    assert.equal(h('a <!-- x\nb\nc --> d\ne'), 'hhh.')
    assert.equal(h('<!-- a --> <!-- b\nc\n-->\nd'), 'hhh.')
    assert.equal(h('<!--\n```\n-->\nb'), 'hhh.')
    assert.equal(h('```\n<!--\n```\nb'), 'hhh.')
    assert.equal(h('a -->\nb'), '..')
    assert.equal(h('<!-- a\nb'), 'hh')
    assert.equal(h('--> <!--\nb\n-->c\nd'), 'hhh.')
    assert.equal(h('``` <!--\nb\n-->\nc\n```\nd'), 'hhhhh.')
    assert.equal(h('<!-- a -->```\nb'), 'h.')
    assert.equal(h('<!--\n-->```\nb\n```'), 'hh.h')
    assert.equal(h('<!--\n``` -->\nb'), 'hh.')
})

test('g release notes that carry the section', () => {
    const two = parseRecord(record([E1, E2]))
    const three = parseRecord(record([E1, E2, E3]))
    for (const section of [none(2), expectedSection(parseRecord(record([E1])), two), expectedSection(null, three)]) {
        assert.deepEqual(bodyFailures(section, section), [])
        assert.deepEqual(bodyFailures(section + '\n', section), [])
        assert.deepEqual(bodyFailures('## Highlights\n- one\n\n' + section + '\n\n## Security\n- two\n', section), [])
        assert.deepEqual(bodyFailures(('## Highlights\n- one\n\n' + section + '\n\n## Security\n- two\n').replace(/\n/g, '\r\n'), section), [])
        assert.deepEqual(bodyFailures(section + '\n\nMore words about RO-1.\n\n## Other\n\n- RO-9: a list line under another heading\n', section), [])
        assert.deepEqual(bodyFailures('```\ncode\n```\n<!-- note -->\n' + section + '\n<details>\n\n```\nlater\n```\n', section), [])
    }
})

test('h release notes that do not carry the section', () => {
    const section = expectedSection(parseRecord(record([E1])), parseRecord(record([E1, E2, E3])))
    const want = section.split('\n')
    const missing = 'the release notes have no line `' + HEADING + '`'
    const near = n => 'line ' + n + ' looks like the heading and is not it: write exactly `' + HEADING + '` on a line of its own'
    const not = (n, found) => 'line ' + n + ' of the release notes is not the line the change record gives: expected `' + want[n - 1] + '`, found '
        + (found === undefined ? 'the end of the notes' : JSON.stringify(found))
    const extra = n => 'line ' + n + ' of the release notes names an entry the change record does not add in this release'
    assert.deepEqual(bodyFailures(null, section), ['the release has no notes'])
    assert.deepEqual(bodyFailures(undefined, section), ['the release has no notes'])
    assert.deepEqual(bodyFailures('', section), [missing])
    assert.deepEqual(bodyFailures('## Highlights\n- Regulated output changes are in the docs.\n', section), [missing])
    for (const form of ['## regulated output changes', '## Regulated Output Changes', '### Regulated output changes', '# Regulated output changes',
        '## Regulated output changes ', ' ## Regulated output changes', '##  Regulated output changes', '## Regulated output changes:',
        '## **Regulated output changes**', '**Regulated output changes**', 'Regulated output changes', '> ## Regulated output changes',
        '## Regulated outputs changes', '## Regulated output change', '## Regulated-output changes', '## Regulated output changes ##',
        '    ## Regulated output changes', '##\tRegulated output changes']) {
        assert.deepEqual(bodyFailures(section.replace(HEADING, form), section), [near(1), missing], form)
    }
    assert.deepEqual(bodyFailures(section.replace(HEADING, '## Regulated output changes\u00a0'), section), [near(1), missing])
    assert.deepEqual(bodyFailures(section.replace(/\n/g, '\r'), section), [missing])
    assert.deepEqual(bodyFailures(section + '\r', section), [not(6, want[5] + '\r')])
    assert.deepEqual(bodyFailures(want[0] + '\r\r\n' + want.slice(1).join('\r\n'), section), [near(1), missing])
    assert.deepEqual(bodyFailures(section + '\n\n' + none(3), section), ['the release notes have the line `' + HEADING + '` 2 times'])
    assert.deepEqual(bodyFailures(none(3) + '\n\n' + section, section), ['the release notes have the line `' + HEADING + '` 2 times'])
    assert.deepEqual(bodyFailures('```\n' + HEADING + '\n```\n' + section, section), ['the release notes have the line `' + HEADING + '` 2 times'])
    const hiddenAt = n => 'line ' + n + ' of the release notes is inside a code block or a comment: a reader does not see it'
    assert.deepEqual(bodyFailures('```\n' + section + '\n```\n', section), [hiddenAt(2)])
    assert.deepEqual(bodyFailures('~~~md\n' + section + '\n~~~\n', section), [hiddenAt(2)])
    assert.deepEqual(bodyFailures('<!--\n' + section + '\n-->\n', section), [hiddenAt(2)])
    assert.deepEqual(bodyFailures('x <!--\n' + section + '\n--> y\n', section), [hiddenAt(2)])
    assert.deepEqual(bodyFailures(want.slice(0, 3).join('\n') + '\n<!--\n' + want.slice(3).join('\n') + '\n-->', section), [not(4, '<!--'), extra(7)])
    assert.deepEqual(bodyFailures(want.slice(0, 5).join('\n') + '\n<!-- ' + want[5] + ' -->', section), [not(6, '<!-- ' + want[5] + ' -->')])
    assert.deepEqual(bodyFailures(want.slice(0, 5).join('\n') + '\n```\n' + want[5] + '\n```', section), [not(6, '```'), extra(7)])
    assert.deepEqual(bodyFailures(want.slice(0, 4).join('\n') + ' <!--\n' + want.slice(4).join('\n') + '\n-->', section), [not(4, ' <!--')])
    assert.deepEqual(bodyFailures('<!-- x\n-->' + section, section), ['the release notes have no line `' + HEADING + '`'])
    assert.deepEqual(bodyFailures('<!-- x\ny -->\n' + section, section), [])
    assert.deepEqual(bodyFailures(want.slice(0, 2).join('\n') + '\n' + want[2] + ' <!-- x', section), [not(3, want[2] + ' <!-- x')])
    // a line of the notes is quoted, so a control character of it never reaches the log as it is
    assert.deepEqual(bodyFailures(want.slice(0, 2).join('\n') + '\n::error::x\u001b[2K\ry', section), ['line 3 of the release notes is not the line the'
        + ' change record gives: expected `' + want[2] + '`, found "::error::x\\u001b[2K\\ry"'])
    // each line of the section, alone
    const edits = [[2, 'x'], [3, want[2].replace('RO-2 to RO-3', 'RO-2 to RO-2')], [3, want[2].replace('entries RO-2', 'entries RO-1')],
        [3, want[2].replace(URL, URL.replace('https', 'http'))], [3, want[2].replace('ReARM Pro', 'ReARM')], [3, want[2].toLowerCase()],
        [3, want[2] + ' '], [3, ' ' + want[2]], [3, want[2].slice(0, -1)], [4, '- RO-1: x'], [5, want[4].replace('RO-2', 'RO-3')],
        [5, want[4].replace(E2.title, 'Readiness rules')], [5, want[4].replace(E2.action, 'Nothing.')], [5, want[4].replace('- RO', '* RO')],
        [5, want[4] + '.'], [5, want[4].replace('. What to do: ', '. ')], [6, want[5].replace('RO-3', 'RO-4')], [6, want[5].replace(E3.action, E2.action)],
        [6, want[5].replace(E3.title, E2.title)], [6, want[4]], [5, want[5]]]
    for (const [n, found] of edits) {
        const lines = [...want]
        lines[n - 1] = found
        assert.deepEqual(bodyFailures(lines.join('\n'), section), [not(n, found)], n + ': ' + found)
    }
    for (let n = 2; n <= want.length; n++) {
        assert.deepEqual(bodyFailures(want.slice(0, n - 1).join('\n'), section), [not(n, undefined)], 'cut before line ' + n)
        const without = [...want]
        without.splice(n - 1, 1)
        assert.equal(bodyFailures(without.join('\n'), section).length, 1, 'without line ' + n)
        assert.match(bodyFailures(without.join('\n'), section)[0], new RegExp('^line ' + n + ' of the release notes is not the line'))
    }
    assert.deepEqual(bodyFailures(none(3), section), [not(3, none(3).split('\n')[2])])
    // a line of the section that hides what follows it: the section is there, and a reader does not see all of it
    const hiding = expectedSection(null, parseRecord(record([E1, { title: 'Words <!-- and more', action: 'Read.' }])))
    assert.deepEqual(bodyFailures(hiding, hiding), [hiddenAt(6)])
    assert.deepEqual(bodyFailures(hiding + '\n-->', hiding), [hiddenAt(6)])
    assert.deepEqual(bodyFailures(section, none(3)), ['line 3 of the release notes is not the line the change record gives: expected `'
        + none(3).split('\n')[2] + '`, found ' + JSON.stringify(want[2]), 'line 5 of the release notes names an entry the change record does not add in this release',
        'line 6 of the release notes names an entry the change record does not add in this release'])
    assert.deepEqual(bodyFailures(section + '\n- RO-4: One more. What to do: Nothing.', section), [extra(7)])
    assert.deepEqual(bodyFailures(section + '\n\nWords.\n\n* RO-1: again\n', section), [extra(10)])
    assert.deepEqual(bodyFailures(section + '\n  + RO-7 and more\n', section), [extra(7)])
    assert.deepEqual(bodyFailures(none(3) + '\n- RO-3: ' + E3.title, none(3)), [extra(4)])
})

test('i the pages the record names are the pages of this documentation', () => {
    const r = parseRecord(record([E1]))
    const named = sha256('page')
    const D1 = { d1: 'a reason' }
    assert.deepEqual(pageFailures(r, { 'readiness-rules': Buffer.from('page') }, D1), [])
    assert.deepEqual(pageFailures(r, {}, D1), [])
    // a declared identifier is compared with a page or named with the reason why not; never neither, never both
    const uncompared = 'the change record declares d1 and this documentation compares it with nothing: add its page to PAGES, or the'
        + ' identifier to NOT_COMPARED with the reason'
    assert.deepEqual(pageFailures(r, { 'readiness-rules': Buffer.from('page') }), [uncompared])
    assert.deepEqual(pageFailures(r, {}, {}), [uncompared])
    assert.deepEqual(pageFailures(r, { d1: Buffer.from('y') }, {}), ['the change record names d1 as x1 and the page in this documentation is '
        + sha256('y') + ': the page changed and the record does not follow it yet, or the copy of the record here is older than the record of ReARM Pro. The record follows a change in ReARM Pro first (before the first ReARM release that carries RO-1, in RO-1, in place; from that release on, in a new entry), then it is copied here'])
    assert.deepEqual(pageFailures(r, { d1: Buffer.from('y') }, D1).slice(0, 1), ['d1 is in PAGES and in NOT_COMPARED'])
    assert.deepEqual(pageFailures(r, {}, { d1: 'r', k1: 'r' }), ['NOT_COMPARED names k1, which the change record does not declare'])
    assert.deepEqual(pageFailures(r, {}, { d1: 'r', gone: 'r' }), ['NOT_COMPARED names gone, which the change record does not declare'])
    assert.deepEqual(pageFailures(r, {}, { d1: 'r', k9: 'r' }), ['NOT_COMPARED names k9, which the change record does not have'])
    // a pin of rebom: declared, compared by `pins`, `section` and `release`, and in no other list
    assert.deepEqual(pageFailures(r, {}, {}, ['d1']), [])
    assert.deepEqual(pageFailures(r, { d1: Buffer.from('x1') }, {}, ['d1']).slice(0, 1), ['d1 is in PINS and in PAGES'])
    assert.deepEqual(pageFailures(r, {}, D1, ['d1']), ['d1 is in PINS and in NOT_COMPARED'])
    assert.deepEqual(pageFailures(r, {}, D1, ['k1']), ['PINS names k1, which the change record does not declare'])
    assert.deepEqual(pageFailures(r, {}, D1, ['k9']), ['PINS names k9, which the change record does not have'])
    const other = sha256('page\n')
    assert.deepEqual(pageFailures(r, { 'readiness-rules': Buffer.from('page\n') }, D1), ['the change record names readiness-rules as ' + named
        + ' and the page in this documentation is ' + other + ': the page changed and the record does not follow it yet, or the copy of the record here is older than the record of ReARM Pro. The record follows a change in ReARM Pro first (before the first ReARM release that carries RO-1, in RO-1, in place; from that release on, in a new entry), then it is copied here'])
    assert.deepEqual(pageFailures(r, { 'readiness-rules': Buffer.from('Page') }, D1).length, 1)
    const upper = parseRecord(record([E1]).replace(named, named.toUpperCase()))
    assert.equal(pageFailures(upper, { 'readiness-rules': Buffer.from('page') }, D1).length, 1)
    const cut = parseRecord(record([E1]).replace(named, named.slice(0, 12)))
    assert.equal(pageFailures(cut, { 'readiness-rules': Buffer.from('page') }, D1).length, 1)
    // a sum that agrees in its first 16 and in its last 16 digits and not between is another sum
    for (const near of [named.slice(0, 16) + 'f'.repeat(48), '0'.repeat(48) + named.slice(48), named.slice(0, 63) + (named[63] === '0' ? '1' : '0')]) {
        assert.notEqual(near, named)
        assert.equal(pageFailures(parseRecord(record([E1]).replace(named, near)), { 'readiness-rules': Buffer.from('page') }, D1).length, 1, near)
    }
    assert.deepEqual(pageFailures(r, { 'readiness-rules': Buffer.from('') }, D1).length, 1)
    assert.deepEqual(pageFailures(r, { 'readiness-rules': null }, D1), ['the change record names readiness-rules and the documentation has no such page'])
    assert.deepEqual(pageFailures(r, { 'fda-checker-rules': Buffer.from('page') }, D1), ['the change record has no identifier fda-checker-rules'])
    assert.deepEqual(pageFailures(r, { gone: Buffer.from('page') }, D1).length, 1)
    assert.deepEqual(pageFailures(r, { 'readiness-rules': Buffer.from('page'), k1: Buffer.from('B') }, D1).length, 1)
    assert.equal(sha256(''), 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855')
    // this documentation: each page PAGES names is the page the record names
    const real = parseRecord(readFileSync(RECORD, 'utf8'))
    const pages = {}
    for (const [key, file] of Object.entries(PAGES)) pages[key] = readFileSync(file)
    assert.deepEqual(pageFailures(real, pages, NOT_COMPARED, PINS), [])
    assert.ok(Object.keys(PAGES).includes('readiness-rules'))
    // every declared identifier of the real record is compared here or named with a reason, and each reason is a sentence
    const declared = [...real.held].filter(([, kind]) => kind === 'declared').map(([key]) => key).sort()
    assert.deepEqual(declared, [...Object.keys(NOT_COMPARED), ...PINS, ...Object.keys(PAGES).filter(k => real.held.get(k) === 'declared')].sort())
    assert.deepEqual(pageFailures(real, pages, NOT_COMPARED).length, PINS.length)
    for (const reason of Object.values(NOT_COMPARED)) assert.match(reason, /^[A-Za-z].{20,}$/)
    // round 5 (FDAW-125 D8): as many failures as declared identifiers that no page of PAGES compares, however many PAGES lists
    assert.deepEqual(pageFailures(real, pages, {}).length, declared.filter(k => !Object.hasOwn(PAGES, k)).length)
})

test('j release tags and their order', () => {
    for (const t of ['26.11.17', '25.05.6', '26.07.158', '26.01.0', '99.12.999999']) assert.equal(isProductTag(t), true, t)
    for (const t of ['dependency-track-5-helm-0.1.3', 'v26.11.17', '26.11.17 ', ' 26.11.17', '26.11', '26.11.17.1', '26.11.017', '2026.11.17',
        '26.1.17', '26.11.', '26.11.x', '26-11-17', '26.11.17\n', '26.11.1234567', '', null, undefined, 261117, ['26.11.17']]) {
        assert.equal(isProductTag(t), false, String(t))
    }
    const tags = ['26.02.51', '26.01.34', 'dependency-track-5-helm-0.1.3', '26.01.129', '26.01.15', '25.11.9', '25.11.34', 'x']
    assert.equal(previousTag(tags, '26.02.51'), '26.01.129')
    assert.equal(previousTag(tags, '26.01.129'), '26.01.34')
    assert.equal(previousTag(tags, '26.01.34'), '26.01.15')
    assert.equal(previousTag(tags, '26.01.15'), '25.11.34')
    assert.equal(previousTag(tags, '25.11.34'), '25.11.9')
    assert.equal(previousTag(tags, '25.11.9'), null)
    assert.equal(previousTag(tags, '26.03.1'), '26.02.51')
    assert.equal(previousTag(['26.02.51'], '26.02.51'), null)
    assert.equal(previousTag([], '26.02.51'), null)
    assert.equal(previousTag(['27.01.1', '26.10.2', '26.9.99', '26.09.3'], '26.10.10'), '26.10.2')
    assert.equal(previousTag(['25.12.40', '26.01.2'], '26.01.10'), '26.01.2')
    assert.equal(previousTag(['25.12.40', '26.02.2'], '26.01.10'), '25.12.40')
    assert.equal(previousTag(['26.01.34', '26.02.050', '26.02.50-rc1', '26.2.50', '26.02'], '26.02.51'), '26.01.34')
})

const PATH = 'documentation_site/docs/workflows/regulated-output-changes.md'
const COMMIT = 'c'.repeat(40)
const OTHER = 'd'.repeat(40)
// A repository of the cells: files by tag, or by the 40 digits of a commit; `commits` maps a revision to its commit;
// `rebom` the build files of rebom by tag or commit, GOOD where it names none.
const REPO = (files, tags = ['26.11.17', '26.12.5', '26.12.40', 'dependency-track-5-helm-0.1.3'], shallow = false, commits = {}, rebom = {}) => ({
    shallow: () => shallow,
    tags: () => tags,
    commit: rev => Object.hasOwn(commits, rev) ? commits[rev] : null,
    read: (ref, p) => {
        assert.ok(isReadableRef(ref), ref)
        const key = ref.startsWith('refs/tags/') ? ref.slice(10) : ref
        if (p === PATH) return Object.hasOwn(files, key) ? files[key] : null
        const tree = Object.hasOwn(rebom, key) ? rebom[key] : GOOD
        if (p === DOCKERFILE) return tree.dockerfile
        const arch = /^rebom-backend\/tools\.([a-z0-9_]+)\.sha256$/.exec(p)
        assert.ok(arch && Object.hasOwn(tree.tools, arch[1]), p)
        return tree.tools[arch[1]]
    },
    list: (ref, dir) => {
        assert.ok(isReadableRef(ref), ref)
        assert.equal(dir, 'rebom-backend')
        const key = ref.startsWith('refs/tags/') ? ref.slice(10) : ref
        const tree = Object.hasOwn(rebom, key) ? rebom[key] : GOOD
        return [...(tree.dockerfile === null ? [] : [DOCKERFILE]), 'rebom-backend/src', 'rebom-backend/tools.sha256.bak',
            ...Object.keys(tree.tools).map(a => 'rebom-backend/tools.' + a + '.sha256')]
    }
})

test('k one release, checked', () => {
    const repo = REPO
    const r1 = pinned([E1])
    const r2 = pinned([E1, E2])
    const s2 = expectedSection(parseRecord(r1), parseRecord(r2))
    assert.deepEqual(checkRelease('26.12.40', s2, repo({ '26.12.5': r1, '26.12.40': r2 })), { failures: [], section: s2, before: '26.12.5', pins: [] })
    assert.deepEqual(checkRelease('26.12.40', none(2), repo({ '26.12.5': r2, '26.12.40': r2 })), { failures: [], section: none(2), before: '26.12.5', pins: [] })
    // the release before is the tag before, not the first or the newest
    assert.deepEqual(checkRelease('26.12.40', none(2), repo({ '26.11.17': r1, '26.12.5': r2, '26.12.40': r2 })), { failures: [], section: none(2), before: '26.12.5', pins: [] })
    assert.deepEqual(checkRelease('26.12.5', s2, repo({ '26.11.17': r1, '26.12.5': r2, '26.12.40': pinned([E1, E2, E3]) })), { failures: [], section: s2, before: '26.11.17', pins: [] })
    const all = expectedSection(null, parseRecord(r2))
    assert.deepEqual(checkRelease('26.12.40', all, repo({ '26.12.40': r2 })), { failures: [], section: all, before: '26.12.5', pins: [] })
    assert.deepEqual(checkRelease('26.12.40', none(2), repo({ '26.12.40': r2 })).failures.length, 1)
    assert.deepEqual(checkRelease('26.12.40', 'no heading', repo({ '26.12.5': r1, '26.12.40': r2 })),
        { failures: ['the release notes have no line `' + HEADING + '`'], section: s2, before: '26.12.5', pins: [] })
    assert.deepEqual(checkRelease('26.12.40', null, repo({ '26.12.5': r1, '26.12.40': r2 })),
        { failures: ['the release has no notes'], section: s2, before: '26.12.5', pins: [] })
    for (const tag of ['dependency-track-5-helm-0.1.3', 'v26.12.40', '', null]) {
        assert.deepEqual(checkRelease(tag, 'x', repo({})), { skipped: 'the tag ' + JSON.stringify(tag) + ' is not a ReARM release tag' })
    }
    // a tag that starts with a digit and is not the form of a release is refused, never skipped: for the release check and for the author
    for (const tag of ['2026.12.40', '26.12', '26.12.040', '26.1.3', '26.12.40-rc1', '26.12.40 ', '26.12.40\n', '26.12.40; x', '1', '26.12.1234567']) {
        assert.deepEqual(checkRelease(tag, 'x', repo({})), { failures: [NOT_THE_FORM(tag)] }, JSON.stringify(tag))
        assert.deepEqual(checkRelease(tag, 'x', repo({}), { at: 'HEAD', author: true }), { failures: [NOT_THE_FORM(tag)] }, JSON.stringify(tag))
    }
    assert.equal(NOT_THE_FORM('26.1.3'), 'the tag "26.1.3" starts with a digit and is not of the form YY.MM.N of a ReARM release: nothing was'
        + ' checked. If ReARM numbers its releases another way now, this check has to learn it')
    assert.deepEqual(checkRelease('26.12.40', 'x', repo({})), { skipped: 'neither 26.12.5 nor 26.12.40 carries a change record' })
    assert.deepEqual(checkRelease('26.12.40', 'x', repo({ '26.11.17': r1 })), { skipped: 'neither 26.12.5 nor 26.12.40 carries a change record' })
    assert.deepEqual(checkRelease('26.12.40', none(1), repo({ '26.12.5': r1 })), { failures: ['the release 26.12.5 carried the change record and 26.12.40 does not'] })
    assert.deepEqual(checkRelease('26.12.40', s2, repo({ '26.12.5': r1, '26.12.40': r2 }, undefined, true)),
        { failures: ['the checkout is shallow: the release before this one cannot be read; fetch the whole history with its tags'] })
    assert.deepEqual(checkRelease('26.12.40', s2, repo({ '26.12.5': r1, '26.12.40': r2 }, ['26.12.5'])),
        { failures: ['the tag 26.12.40 is not in this checkout: fetch the tags'] })
    assert.equal(TAG_NOT_HERE('26.12.40'), 'the tag 26.12.40 is not in this checkout: fetch the tags')
    const noneBefore = 'no ReARM release tag before 26.12.40 is in this checkout: fetch the tags (ReARM has releases before every release this'
        + ' check reads)'
    assert.deepEqual(checkRelease('26.12.40', s2, repo({ '26.12.40': r2 }, ['26.12.40', 'x'])), { failures: [noneBefore] })
    assert.deepEqual(checkRelease('26.12.40', s2, repo({ '26.12.40': r2 }, ['26.12.40', '27.01.3'])), { failures: [noneBefore] })
    const is = ' (the release before is 26.12.5)'
    assert.deepEqual(checkRelease('26.12.40', s2, repo({ '26.12.5': r2, '26.12.40': r1 })),
        { failures: ['RO-2 was in the release before and is not in this record: it was taken out after that release, or this commit is on an older line than that release. A release carries every entry of the release before it, unchanged' + is], before: '26.12.5' })
    assert.deepEqual(checkRelease('26.12.40', s2, repo({ '26.12.5': r1, '26.12.40': r2.replace('- What changed: w', '- What changed: W') })),
        { failures: ['RO-1 is not the entry the release before carried under that number: it was edited after that release, or this commit is on an older line that numbered an entry of its own. A release carries every entry of the release before it, unchanged' + is], before: '26.12.5' })
    assert.deepEqual(checkRelease('26.12.40', s2, repo({ '26.12.5': r1, '26.12.40': r2.replace('record at RO-2', 'record at RO-3') })),
        { failures: ['the change record says it stands at RO-3 and holds 2 entries' + is], before: '26.12.5' })
    assert.deepEqual(checkRelease('26.12.40', s2, repo({ '26.12.5': 'junk\n', '26.12.40': r2 })),
        { failures: ['the change record does not start with its title line' + is], before: '26.12.5' })
})

test('l the section before the tag exists, and what the author of the notes is told', () => {
    const r1 = pinned([E1])
    const r2 = pinned([E1, E2])
    const s2 = expectedSection(parseRecord(r1), parseRecord(r2))
    const tags = ['26.11.17', '26.12.5', 'dependency-track-5-helm-0.1.3']
    const at = { HEAD: COMMIT, [COMMIT]: COMMIT, 'refs/tags/26.12.5': OTHER, 'origin/main': OTHER }
    // the tag is not made yet: the record of the release is read from the commit, the release before from its tag
    assert.deepEqual(checkRelease('26.12.40', null, REPO({ '26.12.5': r1, [COMMIT]: r2 }, tags, false, at), { at: 'HEAD', author: true }),
        { failures: ['the release has no notes'], section: s2, before: '26.12.5', pins: [] })
    assert.deepEqual(checkRelease('26.12.40', s2, REPO({ '26.12.5': r1, [COMMIT]: r2 }, tags, false, at), { at: COMMIT, author: true }),
        { failures: [], section: s2, before: '26.12.5', pins: [] })
    assert.deepEqual(checkRelease('26.12.40', none(1), REPO({ '26.12.5': r1, [COMMIT]: r1, [OTHER]: r2 }, tags, false, at), { at: 'HEAD' }),
        { failures: [], section: none(1), before: '26.12.5', pins: [] })
    // the commit is the one read: another commit gives another section
    assert.equal(checkRelease('26.12.40', null, REPO({ '26.12.5': r1, [COMMIT]: r1, [OTHER]: r2 }, tags, false, at), { at: 'origin/main' }).section, s2)
    // without --at, the author is told why the tag is missing and what to run; the release check keeps its own sentence
    const made = 'the tag 26.12.40 is not in this checkout. Before the release is published the tag does not exist: run `section 26.12.40'
        + ' --at <the commit the release will be made from>`. After it is published: git fetch --tags'
    assert.equal(TAG_NOT_MADE('26.12.40'), made)
    assert.deepEqual(checkRelease('26.12.40', null, REPO({ '26.12.5': r1 }, tags), { author: true }), { failures: [made] })
    assert.deepEqual(checkRelease('26.12.40', null, REPO({ '26.12.5': r1 }, tags), {}), { failures: [TAG_NOT_HERE('26.12.40')] })
    assert.deepEqual(checkRelease('26.12.40', null, REPO({ '26.12.5': r1 }, tags)), { failures: [TAG_NOT_HERE('26.12.40')] })
    // --at that names nothing, and --at beside a tag that is elsewhere
    assert.deepEqual(checkRelease('26.12.40', null, REPO({ '26.12.5': r1 }, tags, false, at), { at: 'nowhere', author: true }),
        { failures: ['--at "nowhere" names no commit of this checkout'] })
    const tagged = [...tags, '26.12.40']
    assert.deepEqual(checkRelease('26.12.40', null, REPO({ '26.12.5': r1, [COMMIT]: r2 }, tagged, false, { ...at, 'refs/tags/26.12.40': OTHER }),
        { at: 'HEAD', author: true }), { failures: ['the tag 26.12.40 exists and is not at the commit --at names: leave --at out to read the release as tagged'] })
    assert.deepEqual(checkRelease('26.12.40', s2, REPO({ '26.12.5': r1, [COMMIT]: r2 }, tagged, false, { ...at, 'refs/tags/26.12.40': COMMIT }),
        { at: 'HEAD', author: true }), { failures: [], section: s2, before: '26.12.5', pins: [] })
    // --at never lifts the other refusals
    assert.deepEqual(checkRelease('26.12.40', s2, REPO({ '26.12.5': r1, [COMMIT]: r2 }, tags, true, at), { at: 'HEAD' }).failures,
        ['the checkout is shallow: the release before this one cannot be read; fetch the whole history with its tags'])
    assert.equal(checkRelease('26.12.40', s2, REPO({ [COMMIT]: r2 }, ['x'], false, at), { at: 'HEAD' }).failures.length, 1)
    assert.match(checkRelease('26.12.40', s2, REPO({ [COMMIT]: r2 }, ['x'], false, at), { at: 'HEAD' }).failures[0], /^no ReARM release tag before 26\.12\.40 /)
    assert.deepEqual(checkRelease('v1', s2, REPO({}, tags, false, at), { at: 'HEAD' }), { skipped: 'the tag "v1" is not a ReARM release tag' })
    assert.deepEqual(checkRelease('26.12.40', s2, REPO({ '26.12.5': r2, [COMMIT]: r1 }, tags, false, at), { at: 'HEAD' }),
        { failures: ['RO-2 was in the release before and is not in this record: it was taken out after that release, or this commit is on an older line than that release. A release carries every entry of the release before it, unchanged (the release before is 26.12.5)'], before: '26.12.5' })
    // what may reach git as a ref, and what --at may be
    for (const ref of ['refs/tags/26.12.40', COMMIT, 'refs/tags/25.05.6']) assert.equal(isReadableRef(ref), true, ref)
    for (const ref of ['26.12.40', 'HEAD', 'refs/tags/v1', 'refs/heads/main', COMMIT.slice(1), COMMIT + 'c', COMMIT.toUpperCase(), 'refs/tags/26.12.40 ',
        'refs/tags/26.12.40\n', '--help', '', null, undefined, 'refs/tags/', 'refs/tags/26.12.40:x']) assert.equal(isReadableRef(ref), false, String(ref))
    for (const rev of ['HEAD', 'main', 'origin/main', COMMIT, 'abc1234', '26.12.40', 'release-26.12', 'v1_2']) assert.equal(isRevision(rev), true, rev)
    for (const rev of ['-x', '--upload-pack=x', '', ' HEAD', 'HEAD ', 'a b', 'a..b', 'a;b', '$(x)', '`x`', 'a\nb', 'HEAD\n', 'HEAD^', 'HEAD~1', 'a:b', '@{u}',
        'x'.repeat(201), null, undefined, 7]) assert.equal(isRevision(rev), false, String(rev))
    // the arguments of section
    assert.deepEqual(sectionArguments(['26.12.40']), { tag: '26.12.40', notes: undefined, at: undefined })
    assert.deepEqual(sectionArguments(['26.12.40', 'notes.md']), { tag: '26.12.40', notes: 'notes.md', at: undefined })
    assert.deepEqual(sectionArguments(['26.12.40', '--at', 'HEAD']), { tag: '26.12.40', notes: undefined, at: 'HEAD' })
    assert.deepEqual(sectionArguments(['--at', 'HEAD', '26.12.40', 'notes.md']), { tag: '26.12.40', notes: 'notes.md', at: 'HEAD' })
    assert.deepEqual(sectionArguments(['26.12.40', 'notes.md', '--at', COMMIT]), { tag: '26.12.40', notes: 'notes.md', at: COMMIT })
    for (const bad of [[], ['--at', 'HEAD'], ['26.12.40', '--at'], ['26.12.40', '--at', 'a', '--at', 'b'], ['26.12.40', 'n', 'm'], ['26.12.40', '--to', 'x'],
        ['26.12.40', '-a']]) assert.equal(sectionArguments(bad), null, JSON.stringify(bad))
    assert.match(USAGE, /section <tag> \[<file with the release notes>\] \[--at <commit>\]$/)
    assert.match(HOW_TO_MEND, /^Put the lines printed above into the release notes/)
})

// ---- end of fixtures

test('n the pins of rebom, read from its build files', () => {
    const pins = (dockerfile, tools = toolsOf()) => rebomPins(dockerfile, tools)
    assert.deepEqual(PINS, ['rebom-engine', 'rebom-engine-image', 'rebom-tools'])
    assert.deepEqual(pins(dockerSource()), { values: GOOD_PINS, failures: [] })
    // known answers: rebom's sums at rearm 86e5eadd (the source pin), and at 33122cc4 (the release pin 26.10.15 before FDAW-111)
    const X64 = '29138e6068e6cf2dc60e776d078c2b17cbf45750c485aa12c28e1fef5556a15f'
    const ARM = 'abf0b7c5648a5b127791d691cad41f004aceea27c75bb42c9572fdc9694770cf'
    assert.equal(pins(dockerSource(), toolsOf(X64, ARM)).values['rebom-tools'], 'fe6ec753a4b5ab5fc508b957fe4e3c61c3d2a8c078a7d65f0d8cca821676f035')
    assert.deepEqual(pins(dockerRelease('26.10.15'), {
        amd64: X64 + '  /usr/local/bin/cyclonedx-cli\ne6c1d9dbe2f0a8576d71f3697e4df80aff80c0abbe26eb400ff288cc0934ad1b  rearm-26.10.15-linux-amd64.zip\n',
        arm64: ARM + '  /usr/local/bin/cyclonedx-cli\n3dffa8a54cfb0d5f2970814c3081786ad16bf786ee6f7505e496d83618b3cfc1  rearm-26.10.15-linux-arm64.zip\n'
    }), { values: { 'rebom-engine': '26.10.15', 'rebom-engine-image': RELEASE_ZIP, 'rebom-tools': '617279e3c40348ddc11c56907819d990050e03cedfdb309d9e4887cc945acfd9' },
        failures: [] })
    assert.deepEqual(dockerInstructions('# c\nfrom a AS b\nRUN x \\\n  # inside\n  && y\n\nARG Z=1').map(i => i.keyword), ['FROM', 'RUN', 'ARG'])
    // a pin written another way with the same value: the same pins
    for (const d of [dockerSource('ARG REARM_CLI_COMMIT="' + C40 + '"'), dockerSource("ARG REARM_CLI_COMMIT='" + C40 + "'"),
        dockerSource('arg REARM_CLI_COMMIT=' + C40), dockerSource('  ARG   REARM_CLI_COMMIT=' + C40 + '   '),
        dockerSource('ARG GOFLAGS_EXTRA=1 REARM_CLI_COMMIT=' + C40), dockerSource('ARG \\\n    REARM_CLI_COMMIT=' + C40),
        dockerSource().replace(/\n/g, '\r\n'), dockerSource().replace('26.10.17-11-geeeeeeee', '26.11.9'),
        dockerSource(undefined, 'from --platform=$BUILDPLATFORM golang:1.27.0-alpine3.25@sha256:' + GO + ' as rearm-cli'),
        dockerSource().replace('RUN apk add', '# a comment inside the stage\nRUN apk add')]) {
        assert.deepEqual(pins(d), { values: GOOD_PINS, failures: [] }, d.split(/\r?\n/)[5])
    }
    assert.deepEqual(pins(dockerSource(), { arm64: toolsOf().arm64, amd64: toolsOf().amd64 }), { values: GOOD_PINS, failures: [] })
    assert.equal(RELEASE_ZIP, 'release-zip')
    assert.deepEqual(pins(dockerSource(), { amd64: '\n' + SUM_X64 + ' */usr/local/bin/cyclonedx-cli\r\n\n', arm64: '  ' + SUM_ARM + '\t/usr/local/bin/cyclonedx-cli  ' }),
        { values: GOOD_PINS, failures: [] })
    const zz = '9'.repeat(64) + '  /usr/local/bin/zz\n'
    const twoLines = pins(dockerSource(), { amd64: zz + toolsOf().amd64, arm64: toolsOf().arm64 }).values['rebom-tools']
    assert.equal(pins(dockerSource(), { amd64: toolsOf().amd64 + zz, arm64: toolsOf().arm64 }).values['rebom-tools'], twoLines)
    assert.notEqual(twoLines, TOOLS_SUM)
    // another value is another pin, read whole
    assert.equal(pins(dockerSource('ARG REARM_CLI_COMMIT=' + 'e'.repeat(39) + 'f')).values['rebom-engine'], 'e'.repeat(39) + 'f')
    assert.equal(pins(dockerSource(undefined, 'FROM golang:1.26.10@sha256:' + 'c'.repeat(64) + ' AS rearm-cli')).values['rebom-engine-image'], 'sha256:' + 'c'.repeat(64))
    // the image is the one of the stage that sets the pin, not the first FROM of the file
    const later = ['FROM alpine@sha256:' + 'd'.repeat(64) + ' AS first', 'RUN true', ...dockerSource().split('\n')].join('\n')
    assert.equal(pins(later).values['rebom-engine-image'], 'sha256:' + GO)
    // the tools: each arch counts, and each line of it
    for (const t of [toolsOf('3'.repeat(64)), toolsOf(undefined, '3'.repeat(64)), { amd64: toolsOf().amd64 },
        { ...toolsOf(), s390x: SUM_X64 + '  /usr/local/bin/cyclonedx-cli\n' }, { amd64: toolsOf().amd64.replace('cyclonedx-cli\n', 'cyclonedx\n'), arm64: toolsOf().arm64 },
        { amd64: toolsOf().arm64, arm64: toolsOf().amd64 }]) {
        const v = pins(dockerSource(), t)
        assert.deepEqual(v.failures, [], JSON.stringify(t))
        assert.notEqual(v.values['rebom-tools'], TOOLS_SUM, JSON.stringify(t))
    }
    // what cannot be found or read is refused with the reason, never left out
    const refused = (d, t, ...messages) => assert.deepEqual(pins(d, t).failures, messages, String(d).split('\n')[5])
    const noPin = DOCKERFILE + ' has no engine pin this check knows: neither ARG REARM_CLI_COMMIT with a commit nor one ARG REARM_CLI_VERSION with a'
        + ' release YY.MM.N. If rebom pins its engine another way now, this check has to learn it'
    for (const line of ['ARG REARM_CLI_REV=' + C40, '# ARG REARM_CLI_COMMIT=' + C40, 'ENV REARM_CLI_COMMIT=' + C40, 'ARG REARM_CLI_COMMITS=' + C40,
        'ARG XREARM_CLI_COMMIT=' + C40, 'LABEL REARM_CLI_COMMIT=' + C40]) {
        refused(dockerSource(line), toolsOf(), noPin)
        assert.equal(pins(dockerSource(line)).values['rebom-engine'], undefined, line)
    }
    refused(dockerSource('ARG REARM_CLI_COMMIT=' + C40 + '\nARG REARM_CLI_COMMIT=' + C40), toolsOf(),
        DOCKERFILE + ' sets REARM_CLI_COMMIT 2 times: the engine pin is one line')
    const notACommit = v => DOCKERFILE + ' sets REARM_CLI_COMMIT to ' + JSON.stringify(v) + ', which is not a commit of 40 lowercase hex digits (the build refuses it too)'
    for (const v of ['', 'e'.repeat(39), 'e'.repeat(41), C40.toUpperCase(), 'c267f5a1', 'HEAD', 'v26.11.9', '"' + C40]) {
        refused(dockerSource('ARG REARM_CLI_COMMIT=' + v), toolsOf(), notACommit(v))
    }
    refused(dockerSource('ARG REARM_CLI_COMMIT'), toolsOf(), notACommit(''))
    const noDigest = 'the stage of ' + DOCKERFILE + ' that builds the engine (the one that sets REARM_CLI_COMMIT) does not name its image by a sha256 digest'
    for (const from of ['FROM golang:1.26.10-alpine3.24 AS rearm-cli', 'FROM golang:1.26.10@sha256:' + 'a'.repeat(63) + ' AS rearm-cli',
        'FROM golang:1.26.10@sha256:' + GO.toUpperCase() + ' AS rearm-cli', 'FROM golang:1.26.10@sha512:' + GO + GO + ' AS rearm-cli']) {
        refused(dockerSource(undefined, from), toolsOf(), noDigest)
    }
    refused(dockerSource().replace(/^FROM --platform.*\n/m, ''), toolsOf(), noDigest)
    assert.deepEqual(rebomPins(null, toolsOf()), { values: {}, failures: [DOCKERFILE + ' is not in this commit: the pins of rebom cannot be read. If rebom'
        + ' is built from another file now, this check has to learn it'] })
    const noCopy = DOCKERFILE + ' does not copy tools.${TARGETARCH}.sha256: the sums of the tools rebom downloads are not where this check reads them.'
        + ' If they moved, this check has to learn it'
    const copyLine = 'COPY tools.${TARGETARCH}.sha256 /tmp/tools.sha256'
    for (const other of ['COPY tools.amd64.sha256 /tmp/tools.sha256', '# ' + copyLine, 'COPY tools.${TARGETARCH}.sha256 tools.x /tmp/', 'ADD tools.${TARGETARCH}.sha256 /tmp/tools.sha256']) {
        refused(dockerSource().replace(copyLine, other), toolsOf(), noCopy)
    }
    const notChecked = DOCKERFILE + ' copies tools.${TARGETARCH}.sha256 to /tmp/tools.sha256 and no RUN checks it with sha256sum -c'
    refused(dockerSource().replace('    sha256sum -c /tmp/tools.sha256 && \\', '    true && \\'), toolsOf(), notChecked)
    refused(dockerSource().replace('sha256sum -c /tmp/tools.sha256', 'sha256sum -c /tmp/other.sha256'), toolsOf(), notChecked)
    refused(dockerSource().replace('sha256sum -c /tmp/tools.sha256', 'sha256sum /tmp/tools.sha256'), toolsOf(), notChecked)
    const unreadable = (arch, line) => 'rebom-backend/tools.' + arch + '.sha256 has a line this check cannot read (a sum of 64 lowercase hex digits,'
        + ' then the file): ' + JSON.stringify(line)
    for (const line of [SUM_X64.toUpperCase() + '  /usr/local/bin/cyclonedx-cli', SUM_X64.slice(1) + '  /usr/local/bin/cyclonedx-cli', SUM_X64,
        SUM_X64 + 'a  /usr/local/bin/cyclonedx-cli', 'sha256:' + SUM_X64 + '  /usr/local/bin/cyclonedx-cli']) {
        refused(dockerSource(), { amd64: line + '\n', arm64: toolsOf().arm64 }, unreadable('amd64', line))
        assert.equal(pins(dockerSource(), { amd64: line + '\n', arm64: toolsOf().arm64 }).values['rebom-tools'], undefined)
    }
    refused(dockerSource(), { amd64: toolsOf().amd64, arm64: toolsOf().arm64 + SUM_X64 + '  /usr/local/bin/cyclonedx-cli\n' },
        'rebom-backend/tools.arm64.sha256 names /usr/local/bin/cyclonedx-cli twice')
    refused(dockerSource(), { amd64: '\n', arm64: toolsOf().arm64 }, 'rebom-backend/tools.amd64.sha256 holds no sum')
    refused(dockerSource(), {}, 'rebom-backend has no tools.<arch>.sha256 in this commit')
    // the release pin: the version, and a sum of its zip for every arch
    const rel = pins(dockerRelease(), releaseTools())
    assert.deepEqual([rel.failures, rel.values['rebom-engine'], rel.values['rebom-engine-image']], [[], '26.11.9', RELEASE_ZIP])
    assert.match(rel.values['rebom-tools'], /^[0-9a-f]{64}$/)
    assert.notEqual(rel.values['rebom-tools'], TOOLS_SUM)
    assert.notEqual(pins(dockerRelease('26.11.10'), releaseTools('26.11.10')).values['rebom-tools'], rel.values['rebom-tools'])
    const noZip = (v, arch) => 'the release pin ' + v + ' of ' + DOCKERFILE + ' has no sum of rearm-' + v + '-linux-' + arch + '.zip in rebom-backend/tools.' + arch + '.sha256'
    refused(dockerRelease(), { amd64: releaseTools().amd64, arm64: toolsOf().arm64 }, noZip('26.11.9', 'arm64'))
    refused(dockerRelease('26.11.10'), releaseTools(), noZip('26.11.10', 'amd64'), noZip('26.11.10', 'arm64'))
    for (const v of ['26.10.17-11-gc267f5a1', 'v26.11.9', '26.11', 'latest']) refused(dockerRelease(v), releaseTools(v), noPin)
    refused(dockerRelease() + 'ARG REARM_CLI_VERSION=26.11.9\n', releaseTools(), noPin)
})

test('o the record and the pins of rebom in the same commit', () => {
    const r = parseRecord(pinned([E1]))
    const good = rebomPins(GOOD.dockerfile, GOOD.tools)
    assert.deepEqual(pinFailures(r, good, '27.01.3'), [])
    assert.equal(PIN_MOVED('27.01.3', 'rebom-engine', 'x', 'y'), 'rebom-backend at 27.01.3 gives rebom-engine x and the change record there says y: a'
        + ' pin of rebom moved without its entry, or the copy of the record here is older than the record of ReARM Pro. The record follows the move'
        + ' in ReARM Pro first (before the first ReARM release that carries RO-1, in RO-1, in place; from that release on, in a new entry), then'
        + ' the copy here follows in the same pull request as the move')
    // each pin moved without its entry: said whole, with both values
    for (const key of PINS) {
        const moved = { values: { ...good.values, [key]: 'f'.repeat(40) }, failures: [] }
        assert.deepEqual(pinFailures(r, moved, '27.01.3'), [PIN_MOVED('27.01.3', key, 'f'.repeat(40), GOOD_PINS[key])], key)
    }
    // the record's value is compared whole: a prefix, another case, one digit more, a digit inside, (not held)
    for (const v of [C40.slice(0, 8), C40.toUpperCase(), C40 + 'e', 'e'.repeat(20) + 'f' + 'e'.repeat(19), '(not held)']) {
        const rec = parseRecord(pinned([E1], l => l.map(x => x.replace('| `rebom-engine` | ' + C40 + ' |', '| `rebom-engine` | ' + v + ' |'))))
        assert.equal(rec.keys.get('rebom-engine'), v)
        assert.deepEqual(pinFailures(rec, good, 'x'), [PIN_MOVED('x', 'rebom-engine', C40, v)], v)
    }
    // a record without a pin, or with a pin it does not declare
    const without = parseRecord(pinned([E1], l => l.filter(x => !x.startsWith('| `rebom-tools`'))))
    assert.deepEqual(pinFailures(without, good, '27.01.3'), ['the change record at 27.01.3 has no identifier rebom-tools: it names the pins of rebom from RO-1 on'])
    const computed = parseRecord(pinned([E1], l => l.map(x => x.startsWith('| `rebom-tools`') ? x.replace('declared: by its author', 'computed: by a test') : x)))
    assert.deepEqual(pinFailures(computed, good, '27.01.3'), ['the change record at 27.01.3 holds rebom-tools as computed: a pin of rebom is declared'])
    // what the build files do not give is a failure with its place
    assert.deepEqual(pinFailures(r, rebomPins(null, {}), 'the commit ' + COMMIT), [DOCKERFILE + ' is not in this commit: the pins of rebom cannot be'
        + ' read. If rebom is built from another file now, this check has to learn it (at the commit ' + COMMIT + ')'])
    // in a release: the pins of the release's own tag, beside the notes
    const r1 = pinned([E1])
    const r2 = pinned([E1, E2])
    const s2 = expectedSection(parseRecord(r1), parseRecord(r2))
    const F40 = 'f'.repeat(40)
    const movedTree = { dockerfile: dockerSource('ARG REARM_CLI_COMMIT=' + F40), tools: toolsOf() }
    const repo = (files, rebom, tags, commits = {}) => REPO(files, tags, false, commits, rebom)
    assert.deepEqual(checkRelease('26.12.40', s2, repo({ '26.12.5': r1, '26.12.40': r2 }, { '26.12.40': movedTree })),
        { failures: [], section: s2, before: '26.12.5', pins: [PIN_MOVED('26.12.40', 'rebom-engine', F40, C40)] })
    assert.deepEqual(checkRelease('26.12.40', s2, repo({ '26.12.5': r1, '26.12.40': r2 }, { '26.12.5': movedTree })),
        { failures: [], section: s2, before: '26.12.5', pins: [] })
    const r2moved = r2.replace('| `rebom-engine` | ' + C40 + ' |', '| `rebom-engine` | ' + F40 + ' |')
    const s2m = expectedSection(parseRecord(r1), parseRecord(r2moved))
    assert.deepEqual(checkRelease('26.12.40', s2m, repo({ '26.12.5': r1, '26.12.40': r2moved }, { '26.12.40': movedTree })),
        { failures: [], section: s2m, before: '26.12.5', pins: [] })
    const toolsMoved = { dockerfile: dockerSource(), tools: toolsOf(undefined, '5'.repeat(64)) }
    assert.deepEqual(checkRelease('26.12.40', s2, repo({ '26.12.5': r1, '26.12.40': r2 }, { '26.12.40': toolsMoved })).pins,
        [PIN_MOVED('26.12.40', 'rebom-tools', rebomPins(toolsMoved.dockerfile, toolsMoved.tools).values['rebom-tools'], TOOLS_SUM)])
    // before the tag exists: the pins of the commit --at names
    const at = { HEAD: COMMIT, [COMMIT]: COMMIT }
    assert.deepEqual(checkRelease('27.01.3', null, repo({ '26.12.40': r2, [COMMIT]: r2 }, { [COMMIT]: movedTree }, ['26.12.40'], at), { at: 'HEAD', author: true }).pins,
        [PIN_MOVED('the commit ' + COMMIT, 'rebom-engine', F40, C40)])
    assert.deepEqual(checkRelease('27.01.3', null, repo({ '26.12.40': r2, [COMMIT]: r2 }, { '26.12.40': movedTree }, ['26.12.40'], at), { at: 'HEAD', author: true }).pins, [])
    // rebom's Dockerfile gone at the tag: refused, never nothing to compare
    assert.deepEqual(checkRelease('26.12.40', s2, repo({ '26.12.5': r1, '26.12.40': r2 }, { '26.12.40': { dockerfile: null, tools: toolsOf() } })).pins,
        [DOCKERFILE + ' is not in this commit: the pins of rebom cannot be read. If rebom is built from another file now, this check has to learn it (at 26.12.40)'])
    // no record at all, or one that cannot be read: there is nothing to compare the pins with, and the result says nothing of them
    assert.equal(checkRelease('26.12.40', 'x', repo({}, { '26.12.40': movedTree })).pins, undefined)
    assert.equal(checkRelease('26.12.40', s2, repo({ '26.12.5': 'junk\n', '26.12.40': r2 }, { '26.12.40': movedTree })).pins, undefined)
    // only rebom-backend/tools.<arch>.sha256 are tools
    assert.deepEqual(rebomPinsAt(REPO({}), COMMIT), { values: GOOD_PINS, failures: [] })
    assert.match(HOW_TO_MEND_PINS, /^The pins of rebom and the change record disagree at this commit/)
    assert.match(USAGE, /^usage: regulated-outputs\.mjs pages \| pins \| release \| section /)
})

test('m the commands, run as the documentation build and the author run them', () => {
    const module = fileURLToPath(new globalThis.URL('./regulated-outputs.mjs', import.meta.url))
    const site = fileURLToPath(new globalThis.URL('../../', import.meta.url))
    const runIn = (cwd, ...args) => {
        const r = spawnSync(process.execPath, [module, ...args], { cwd, encoding: 'utf8' })
        return [r.status, r.stdout, r.stderr]
    }
    const run = (...args) => runIn(site, ...args)
    // `pages` is the documentation build's line: the real record, the real pages, the reasons of this module
    assert.deepEqual(run('pages'), [0, '', ''])
    for (const args of [[], ['pages', 'x'], ['pins', 'x'], ['pins', '--at', 'HEAD'], ['release', 'x'], ['section'], ['section', '--at', 'HEAD'], ['section', '26.12.40', '--at'],
        ['section', '26.12.40', '--at', 'a', '--at', 'b'], ['section', '26.12.40', '-x'], ['other']]) {
        assert.deepEqual(run(...args), [2, '', USAGE + '\n'], JSON.stringify(args))
    }
    // a documentation tree whose rules page is not the one the record names: said, and the build line fails
    const other = mkdtempSync(join(tmpdir(), 'regulated-outputs-'))
    try {
        mkdirSync(join(other, 'docs', 'workflows'), { recursive: true })
        copyFileSync(join(site, RECORD), join(other, RECORD))
        // round 5 (FDAW-125 D8): every page PAGES names, so that only the rules page below differs, however many PAGES lists
        for (const file of Object.values(PAGES)) {
            mkdirSync(dirname(join(other, file)), { recursive: true })
            copyFileSync(join(site, file), join(other, file))
        }
        writeFileSync(join(other, PAGES['readiness-rules']), 'another page\n')
        const [status, out, err] = runIn(other, 'pages')
        assert.deepEqual([status, out], [1, ''])
        assert.match(err, /^the change record names readiness-rules as [0-9a-f]{64} and the page in this documentation is [0-9a-f]{64}: the page changed and the record does not follow it yet, or the copy of the record here is older than the record of ReARM Pro\. The record follows a change in ReARM Pro first \(before the first ReARM release that carries RO-1, in RO-1, in place; from that release on, in a new entry\), then it is copied here\n$/)
    } finally {
        rmSync(other, { recursive: true, force: true })
    }
    // a tag of another form is not a release of ReARM: said, and nothing is read
    assert.deepEqual(run('section', 'v1'), [0, 'not checked: the tag "v1" is not a ReARM release tag\n', ''])
    assert.deepEqual(run('section', '26.1.3'), [1, '', NOT_THE_FORM('26.1.3') + '\n'])
})
