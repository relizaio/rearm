import { describe, it, expect } from 'vitest';
import {
    boundFailureText, childFailure, childNotStarted, ChildFailureKind, ChildProcessError,
    FAILURE_TEXT_CAP, REMOVED_MARK
} from '../../src/childFailure';

/**
 * The failure text of a child CLI, bounded and cleaned (TPF-261 design 3.1 to 3.3; rows T-1240 to
 * T-1243). Pure: no child, no logger. USERW, PASSW, BODYW and ARGW are plain marker words.
 */

const ch = (code: number) => String.fromCharCode(code);
const PRINTABLE = /^[\x20-\x7E]*$/;

/** Every input of T-1240 to T-1242, for the idempotence check of T-1240. */
const seen: unknown[] = [];
function bound(raw: unknown): string {
    seen.push(raw);
    return boundFailureText(raw);
}

function expectShape(text: string): void {
    expect(text.length).toBeLessThanOrEqual(FAILURE_TEXT_CAP);
    expect(PRINTABLE.test(text)).toBe(true);
    expect(text).not.toMatch(/ {2}/);
    expect(text).toBe(text.trim());
}

function expectClean(text: string, markers: string[]): void {
    expectShape(text);
    for (const m of markers) expect(text).not.toContain(m);
    expect(text).not.toContain('@');
    expect(text).not.toContain('://');
    expect(text).not.toContain('"');
}

describe('T-1240 boundFailureText: cap, characters, spaces, any input', () => {
    it('a line of 5 000 000 characters with spaces is cut to the cap', () => {
        const text = bound('Error ' + 'abcd efgh '.repeat(500000));
        expectShape(text);
        expect(text.startsWith('Error abcd efgh abcd')).toBe(true);
        expect(text.length).toBeGreaterThan(FAILURE_TEXT_CAP - 10);
    });

    it('control and non-ASCII characters inside the picked line become spaces', () => {
        const controls = [10, 13, 9, 27, 0, 127, 0x00e9, 0x202e, 0xd83d].map(ch);
        // A line feed ends a line: the picked line is the first one starting with Error.
        const raw = 'noise\nError a' + controls.slice(1).join('b') + 'c' + ch(10) + 'tail';
        const text = bound(raw);
        expectShape(text);
        expect(text).toBe('Error a b b b b b b b c');
    });

    it('runs of spaces become one space, no space at either end', () => {
        expect(bound('   Error   lots    of     spaces   ')).toBe('Error lots of spaces');
        expect(bound('  just   words  ')).toBe('just words');
    });

    it('a number, undefined and an object give a bounded text', () => {
        expect(bound(42)).toBe('42');
        expect(bound(undefined)).toBe('undefined');
        expectShape(bound({ a: 'USERW' }));
        expect(bound('')).toBe('');
        expect(bound('\n \n\t\n')).toBe('');
    });
});

describe('T-1241 boundFailureText: a URI with userinfo does not survive in any form', () => {
    const cases: Array<[string, string, string[], string, string | null]> = [
        ['scheme, port, path, query', 'before http://USERW:PASSW@host:8080/x?q=1 after', ['USERW', 'PASSW'], 'before', 'after'],
        ['no scheme', 'before USERW:PASSW@host:8080/x after', ['USERW', 'PASSW'], 'before', 'after'],
        ['percent-encoded userinfo', 'before http://USERW%40corp:PASSW%3A@host/x after', ['USERW', 'PASSW', '%40'], 'before', 'after'],
        ['percent-encoded userinfo, no scheme', 'before USERW%40corp:PASSW@host after', ['USERW', 'PASSW'], 'before', 'after'],
        ['IPv6 host in brackets', 'before http://USERW:PASSW@[::1]:8080/x after', ['USERW', 'PASSW', '::1'], 'before', 'after'],
        ['the form Go prints',
            'Error during enrichment: failed to enrich batch 1-1: error making request to BEAR: Post "http://USERW:***@host/graphql": dial tcp 10.0.0.1:443: connect: connection refused',
            ['USERW', '***', 'host/graphql'], 'error making request to BEAR: Post', 'dial tcp 10.0.0.1:443: connect: connection refused'],
        ['a quoted URL holding a space and an escaped quote',
            'before Post "http://USERW PASSW\\"BODYW ARGW@host/x": connection refused',
            ['USERW', 'PASSW', 'BODYW', 'ARGW'], 'before Post', 'connection refused'],
        ['the scheme error of a URI without a scheme', 'Error: unsupported protocol scheme "USERW" after', ['USERW'], 'Error: unsupported protocol scheme', 'after']
    ];

    for (const [label, raw, markers, before, after] of cases) {
        it(label, () => {
            const text = bound(raw);
            expectClean(text, markers);
            expect(text).toContain(REMOVED_MARK);
            expect(text).toContain(before);
            if (after !== null) expect(text.endsWith(after)).toBe(true);
        });
    }

    it('a bare URI that character 2000 of the line falls inside is dropped by the window rule', () => {
        // Spaces collapse later, so what stood near character 2000 is near the start of the result.
        const raw = 'Error before' + ' '.repeat(1979) + 'USERW:PASSW@host:8080/x after';
        expect(raw.indexOf('USERW')).toBeLessThan(2000);
        expect(raw.indexOf('@')).toBeGreaterThan(2000);
        const text = bound(raw);
        expectClean(text, ['USERW', 'PASSW', 'USE']);
        expect(text).toBe(`Error before ${REMOVED_MARK}`);
    });

    it('a quoted URI that character 2000 of the line falls inside takes the rest of the window with it', () => {
        const raw = 'Error: Post' + ' '.repeat(1980) + '"http://USERW:PASSW@host/x": connection refused';
        expect(raw.indexOf('"')).toBeLessThan(2000);
        expect(raw.indexOf('": ')).toBeGreaterThan(2000);
        const text = bound(raw);
        expectClean(text, ['USERW', 'PASSW']);
        expect(text).toBe(`Error: Post ${REMOVED_MARK}`);
    });

    it('a URI that character 300 of the result would fall inside', () => {
        const words = 'word '.repeat(58);
        const raw = 'Error ' + words + 'http://USERW:PASSW@host:8080/x after';
        const text = bound(raw);
        expectClean(text, ['USERW', 'PASSW']);
        expect(text).toBe('Error ' + words + REMOVED_MARK.slice(0, 4));
        expect(text.length).toBe(FAILURE_TEXT_CAP);
    });

    it('a line of 3000 characters without a space gives the mark alone', () => {
        expect(bound('x'.repeat(3000))).toBe(REMOVED_MARK);
        expect(bound('Error' + 'y'.repeat(2995))).toBe(REMOVED_MARK);
    });

    it('host names and addresses after the URI stay', () => {
        const text = bound('Error: Post "http://USERW:PASSW@bear.internal:8443/graphql": dial tcp 10.0.0.1:443: i/o timeout');
        expect(text).toBe(`Error: Post ${REMOVED_MARK}: dial tcp 10.0.0.1:443: i/o timeout`);
    });
});

describe('T-1242 boundFailureText: nothing follows returned status <n>', () => {
    it('the body of a non-2xx answer is dropped', () => {
        const raw = 'Error during enrichment: failed to enrich batch 1-1: BEAR API returned status 500: {"errors":[{"message":"BODYW"}]} BODYW plain\n'
            + 'BODYW second line\nBODYW third line';
        const text = bound(raw);
        expectShape(text);
        expect(text).toBe('Error during enrichment: failed to enrich batch 1-1: BEAR API returned status 500');
        expect(text).not.toContain('BODYW');
    });

    it('the same phrase without digits is not cut', () => {
        const text = bound('Error: BEAR API returned status : BODYW stays');
        expect(text).toBe('Error: BEAR API returned status : BODYW stays');
    });

    it('a text without the phrase is not cut', () => {
        expect(bound('Error: BEAR API answered 500 BODYW stays')).toBe('Error: BEAR API answered 500 BODYW stays');
    });

    it('the first phrase followed by digits is the one that cuts', () => {
        expect(bound('Error: returned status x then returned status 404: BODYW')).toBe('Error: returned status x then returned status 404');
    });
});

describe('T-1240 boundFailureText applied to its own result returns the same text', () => {
    it('for every input of T-1240, T-1241 and T-1242', () => {
        expect(seen.length).toBeGreaterThan(20);
        for (const raw of seen) {
            const once = boundFailureText(raw);
            expect(boundFailureText(once)).toBe(once);
        }
    });
});

describe('T-1243 childFailure and childNotStarted', () => {
    const ENRICH_STDOUT = [
        'Using BEAR at http://USERW:PASSW@host',
        'Found 1 components to enrich',
        'Processing batch 1-1 of 1',
        'Error during enrichment: x',
        ''
    ].join('\n');
    const exit1 = { killedByTimeout: false, exitCode: 1, signal: null, timeoutMs: 1800000 };

    function expectOwnProperties(error: ChildProcessError): void {
        const own = Object.keys(error).filter((k) => k !== 'name').sort();
        expect(own).toStrictEqual(['exitCode', 'kind', 'signal', 'timeoutMs']);
        if (Object.prototype.hasOwnProperty.call(error, 'name')) expect((error as any).name).toBe('ChildProcessError');
        expect(error.name).toBe('ChildProcessError');
        expect(error).toBeInstanceOf(Error);
    }

    it('the detail is the Error line of a failed enrichment', () => {
        const error = childFailure('rearm', exit1, ENRICH_STDOUT);
        expect(error.message).toBe('rearm exited with code 1: Error during enrichment: x');
        expect(error.kind).toBe(ChildFailureKind.EXIT_CODE);
        expect(error.exitCode).toBe(1);
        expect(error.signal).toBeNull();
        expect(error.timeoutMs).toBeNull();
        expectOwnProperties(error);
        expect(JSON.stringify(error)).not.toContain('USERW');
    });

    it('without an Error line the detail is the last non-blank line', () => {
        const error = childFailure('rearm', exit1, 'Using BEAR at http://USERW:PASSW@host\nProcessing batch 1-1 of 1\n\n  \n');
        expect(error.message).toBe('rearm exited with code 1: Processing batch 1-1 of 1');
    });

    it('the convert-spdx Error: line', () => {
        expect(childFailure('rearm', exit1, 'Error: failed to read SPDX document: y\n').message)
            .toBe('rearm exited with code 1: Error: failed to read SPDX document: y');
    });

    it('empty stdout and blank lines give the reason alone, without a colon', () => {
        expect(childFailure('rearm', exit1, '').message).toBe('rearm exited with code 1');
        expect(childFailure('rearm', exit1, '\n  \n\t\n').message).toBe('rearm exited with code 1');
    });

    it('a signal', () => {
        const error = childFailure('rearm', { killedByTimeout: false, exitCode: null, signal: 'SIGKILL' }, '');
        expect(error.message).toBe('rearm was killed by signal SIGKILL');
        expect(error.kind).toBe(ChildFailureKind.SIGNAL);
        expect(error.signal).toBe('SIGKILL');
        expect(error.exitCode).toBeNull();
        expect(error.timeoutMs).toBeNull();
        expectOwnProperties(error);
    });

    it('a timeout wins over a signal and over a code', () => {
        const bySignal = childFailure('rearm', { killedByTimeout: true, exitCode: null, signal: 'SIGTERM', timeoutMs: 1800000 }, ENRICH_STDOUT);
        expect(bySignal.message).toBe('rearm timed out after 1800000 ms: Error during enrichment: x');
        expect(bySignal.kind).toBe(ChildFailureKind.TIMEOUT);
        expect(bySignal.timeoutMs).toBe(1800000);
        expect(bySignal.signal).toBe('SIGTERM');
        expectOwnProperties(bySignal);
        const byCode = childFailure('rearm', { killedByTimeout: true, exitCode: 7, signal: null, timeoutMs: 1800000 }, '');
        expect(byCode.message).toBe('rearm timed out after 1800000 ms');
        expect(byCode.kind).toBe(ChildFailureKind.TIMEOUT);
        expect(byCode.exitCode).toBe(7);
    });

    it('a child that could not be started', () => {
        const enoent = childNotStarted('rearm', 'ENOENT');
        expect(enoent.message).toBe('rearm could not be started: ENOENT');
        expect(enoent.kind).toBe(ChildFailureKind.NOT_STARTED);
        expect(enoent.exitCode).toBeNull();
        expect(enoent.signal).toBeNull();
        expect(enoent.timeoutMs).toBeNull();
        expectOwnProperties(enoent);
        expect(childNotStarted('rearm').message).toBe('rearm could not be started: unknown');
    });

    it('the detail is bounded and cleaned like any text', () => {
        const error = childFailure('rearm', exit1,
            'Error during enrichment: failed to enrich batch 1-1: BEAR API returned status 502: BODYW ' + 'z'.repeat(5000));
        expect(error.message).toBe('rearm exited with code 1: Error during enrichment: failed to enrich batch 1-1: BEAR API returned status 502');
    });
});
