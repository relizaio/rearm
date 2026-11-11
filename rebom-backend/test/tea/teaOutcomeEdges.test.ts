import { describe, expect, it } from 'vitest';
import { ShellResult } from '../../src/utils';
import { TeaDocumentKind } from '../../src/services/tea/teaDocument';
import {
    findTeaErrorObject, internalMessage, mapTeaRun, proxyVariableNames, TEA_API_VERSION, TEA_DETAIL_MAX, TEA_URL_MAX, teaError, teaOk,
    TeaOutcome, teaResult
} from '../../src/services/tea/teaOutcome';

/**
 * The outcomes, their fixed sentences, and the mapping of a CLI run to one (design 3.3, 3.4),
 * branch by branch: the order in which a run is judged, the real exit code first, the error
 * object only filling fields, every field cut at its bound.
 */

const SENTENCES: Record<string, string> = {
    OK: 'The operation succeeded.',
    PARTIAL: 'Some files of the collection were not fetched; the rows say which and why.',
    NOT_CONFIGURED: 'The TEA operations are not configured on this rebom.',
    CALLER_REFUSED: 'The caller is not allowed to use the TEA operations.',
    NOT_AVAILABLE: 'The TEA client is not available on this rebom.',
    INVALID_REQUEST: 'The request is invalid.',
    BUSY: 'rebom is busy with other TEA operations; try again later.',
    FETCH_NOT_HELD: 'The fetched files are no longer held; fetch the collection again.',
    NOT_FOUND: 'The TEA server does not know the requested object.',
    URL_REFUSED: 'The TEA client refused a URL or a redirect.',
    AUTH: 'The TEA server refused the request for lack of a valid credential.',
    ADDRESS_REFUSED: 'The TEA client refused to connect to an address it does not allow.',
    LIMIT: 'A limit of the TEA client was reached.',
    CONNECT_FAILED: 'The TEA server could not be reached.',
    TIMEOUT: 'The TEA client did not finish in time and was stopped.',
    CONTRACT_MISMATCH: 'The TEA client answered in a form rebom does not accept.',
    FAILED: 'The TEA operation failed.'
};

const DOC = '{"formatVersion":1,"kind":"TEA_PRODUCT","product":{}}';
const MANIFEST = '{"formatVersion":1,"kind":"TEA_DOWNLOAD_MANIFEST","files":[]}';

function run(extra: Partial<ShellResult> = {}): ShellResult {
    return { code: 0, signal: null, stdout: '', stderr: '', timedOut: false, stdoutOverflow: false, stderrOverflow: false, ...extra };
}

function errorLine(code: number, extra: Record<string, unknown> = {}): string {
    return JSON.stringify({ formatVersion: 1, kind: 'TEA_ERROR', exitCode: code, reason: 'SOME_REASON', message: 'm', ...extra }) + '\n';
}

describe('outcomes, sentences and results', () => {
    it('every outcome has its fixed sentence, and the 17 outcomes are exactly these', () => {
        expect(Object.keys(TeaOutcome).sort()).toEqual(Object.keys(SENTENCES).sort());
        for (const [outcome, sentence] of Object.entries(SENTENCES)) {
            expect(teaError(outcome as any, 'R').message, outcome).toBe(sentence);
        }
        expect(TEA_API_VERSION).toBe(1);
    });

    it('an error carries the reason, a printable detail cut at 2048 bytes, and nothing else', () => {
        const error = teaError('FAILED', 'WHY', 'x'.repeat(3000));
        expect(error.detail).toBe('x'.repeat(2048));
        expect(TEA_DETAIL_MAX).toBe(2048);
        expect(TEA_URL_MAX).toBe(2048);
        expect(teaError('FAILED', 'WHY', 'a\nb\u00e9').detail).toBe('a?b?');
        expect(teaError('FAILED', 'WHY', null).detail).toBeNull();
        expect(teaError('FAILED', 'WHY').detail).toBeNull();
        expect(teaError('FAILED', 'WHY', '').detail).toBe('');
        expect(teaError('FAILED', 'WHY')).toEqual({
            reason: 'WHY', cliExitCode: null, message: SENTENCES.FAILED, detail: null, url: null, httpStatus: null, address: null,
            addressClass: null, phase: null, hint: null, ignoredProxyVariables: null
        });
        expect(teaError('FAILED', 'WHY', null, 'own message').message).toBe('own message');
    });

    it('a result is OK with no error, any other outcome with one, the reason INTERNAL unless given', () => {
        expect(teaOk()).toEqual({ teaApiVersion: 1, outcome: 'OK', error: null });
        expect(teaResult('OK', 'IGNORED', 'ignored')).toEqual({ teaApiVersion: 1, outcome: 'OK', error: null });
        const result = teaResult('BUSY');
        expect(result.outcome).toBe('BUSY');
        expect(result.error).toMatchObject({ reason: 'INTERNAL', message: SENTENCES.BUSY, detail: null });
        expect(teaResult('BUSY', 'SLOTS', 'detail', 'text').error).toMatchObject({ reason: 'SLOTS', detail: 'detail', message: 'text' });
        expect(internalMessage('abcd1234')).toBe('Internal error (ref abcd1234)');
    });

    it('the proxy variables named are those set in the environment, an empty value included', () => {
        expect(proxyVariableNames({})).toEqual([]);
        expect(proxyVariableNames({ HTTP_PROXY: 'x', HTTPS_PROXY: '', ALL_PROXY: 'x', NO_PROXY: 'x', http_proxy: 'x', https_proxy: 'x', all_proxy: 'x', no_proxy: 'x' }))
            .toEqual(['HTTP_PROXY', 'HTTPS_PROXY', 'ALL_PROXY', 'NO_PROXY', 'http_proxy', 'https_proxy', 'all_proxy', 'no_proxy']);
        expect(proxyVariableNames({ no_proxy: '1', HTTP_PROXY: 'x', OTHER_PROXY: 'x', proxy: 'x' })).toEqual(['HTTP_PROXY', 'no_proxy']);
    });
});

describe('the CLI\'s error object on standard error', () => {
    it('is the last complete line that is a TEA_ERROR of format version 1, read backwards', () => {
        const first = errorLine(2, { reason: 'FIRST' });
        const last = errorLine(2, { reason: 'LAST' });
        expect(findTeaErrorObject(first + last, 2)?.reason).toBe('LAST');
        expect(findTeaErrorObject(first + 'noise\n' + last + 'more noise\n', 2)?.reason).toBe('LAST');
        expect(findTeaErrorObject(first, 2)?.reason).toBe('FIRST');
    });

    it('skips lines that are not objects of that kind and version, and looks further back', () => {
        const good = errorLine(2, { reason: 'GOOD' });
        for (const bad of ['TEA address refused: x\n', '{not json\n', '[1,2]\n', 'null\n', '5\n', '"x"\n', ' {"formatVersion":1,"kind":"TEA_ERROR","exitCode":2}\n',
            '{"formatVersion":1,"kind":"TEA_PRODUCT","exitCode":2}\n', '{"formatVersion":2,"kind":"TEA_ERROR","exitCode":2}\n',
            '{"formatVersion":"1","kind":"TEA_ERROR","exitCode":2}\n', '{"kind":"TEA_ERROR","exitCode":2}\n', '{"formatVersion":1,"exitCode":2}\n', '\n']) {
            expect(findTeaErrorObject(good + bad, 2)?.reason, JSON.stringify(bad)).toBe('GOOD');
        }
        // a line that begins with a bracket is not one
        expect(findTeaErrorObject('[{"formatVersion":1,"kind":"TEA_ERROR","exitCode":2}]\n', 2)).toBeNull();
    });

    it('does not count a last part without its line feed, which is not a complete line', () => {
        const partial = errorLine(2, { reason: 'PARTIAL_LINE' }).trimEnd();
        expect(findTeaErrorObject(partial, 2)).toBeNull();
        expect(findTeaErrorObject(errorLine(2, { reason: 'COMPLETE' }) + partial, 2)?.reason).toBe('COMPLETE');
        expect(findTeaErrorObject('', 2)).toBeNull();
        expect(findTeaErrorObject('\n', 2)).toBeNull();
    });

    it('counts only when its exit code is the real one, and the nearest object decides', () => {
        expect(findTeaErrorObject(errorLine(3), 2)).toBeNull();
        expect(findTeaErrorObject(errorLine(2), null)).toBeNull();
        expect(findTeaErrorObject(errorLine(2, { reason: 'EARLIER' }) + errorLine(3, { reason: 'LATER' }), 2)).toBeNull();
        expect(findTeaErrorObject(errorLine(2), 2)).toMatchObject({ exitCode: 2 });
    });
});

describe('a run mapped to an outcome', () => {
    it('is judged in order: the process that never started, the deadline, the overflow, the signal', () => {
        const spawnError = Object.assign(new Error('x'), { code: 'ENOENT' });
        expect(mapTeaRun(run({ spawnError, timedOut: true, stdoutOverflow: true, code: null }), TeaDocumentKind.PRODUCT, {}))
            .toMatchObject({ outcome: 'NOT_AVAILABLE', error: { reason: 'CLI_MISSING', cliExitCode: null } });
        expect(mapTeaRun(run({ timedOut: true, stdoutOverflow: true, code: null }), TeaDocumentKind.PRODUCT, {}))
            .toMatchObject({ outcome: 'TIMEOUT', error: { reason: 'KILLED_AT_DEADLINE' } });
        expect(mapTeaRun(run({ stdoutOverflow: true, code: null }), TeaDocumentKind.PRODUCT, {}))
            .toMatchObject({ outcome: 'CONTRACT_MISMATCH', error: { reason: 'OUTPUT_OVERFLOW' } });
        expect(mapTeaRun(run({ code: null, signal: 'SIGKILL' }), TeaDocumentKind.PRODUCT, {}))
            .toMatchObject({ outcome: 'FAILED', error: { reason: 'SIGNAL', cliExitCode: null } });
    });

    it('exit 0 with the expected document is OK with the line as written; otherwise a contract mismatch naming why', () => {
        const ok = mapTeaRun(run({ stdout: DOC + '\n' }), TeaDocumentKind.PRODUCT, {});
        expect(ok).toEqual({ outcome: 'OK', error: null, line: DOC, doc: { formatVersion: 1, kind: 'TEA_PRODUCT', product: {} } });
        for (const [stdout, reason] of [['', 'OUTPUT_NOT_JSON'], ['[]', 'OUTPUT_NOT_JSON'], [DOC.replace('"formatVersion":1', '"formatVersion":2'), 'FORMAT_VERSION'], [DOC, 'KIND']] as const) {
            const kind = reason === 'KIND' ? TeaDocumentKind.COMPONENT : TeaDocumentKind.PRODUCT;
            const mapped = mapTeaRun(run({ stdout }), kind, {});
            expect(mapped, reason).toEqual({ outcome: 'CONTRACT_MISMATCH', error: expect.objectContaining({ reason, cliExitCode: 0, message: SENTENCES.CONTRACT_MISMATCH }) });
        }
    });

    it('exit 7 is a partial download only for a download: the manifest, the PARTIAL reason and the exit code', () => {
        const partial = mapTeaRun(run({ code: 7, stdout: MANIFEST }), TeaDocumentKind.DOWNLOAD_MANIFEST, {});
        expect(partial.outcome).toBe('PARTIAL');
        expect(partial.error).toMatchObject({ reason: 'PARTIAL', cliExitCode: 7, message: SENTENCES.PARTIAL });
        expect(partial.line).toBe(MANIFEST);
        expect(partial.doc).toMatchObject({ kind: 'TEA_DOWNLOAD_MANIFEST' });
        // any other verb: exit 7 is not in its contract, whatever the document
        expect(mapTeaRun(run({ code: 7, stdout: DOC }), TeaDocumentKind.PRODUCT, {}))
            .toEqual({ outcome: 'CONTRACT_MISMATCH', error: expect.objectContaining({ reason: 'UNEXPECTED_PARTIAL', cliExitCode: 7 }) });
        // a download that exits 7 with a document that does not parse: the reason stays UNEXPECTED_PARTIAL
        for (const stdout of ['', 'x', DOC]) {
            expect(mapTeaRun(run({ code: 7, stdout }), TeaDocumentKind.DOWNLOAD_MANIFEST, {}).error, stdout)
                .toMatchObject({ reason: 'UNEXPECTED_PARTIAL', cliExitCode: 7 });
        }
        // a download that exits 0 with a bad document names the document's fault
        expect(mapTeaRun(run({ code: 0, stdout: '' }), TeaDocumentKind.DOWNLOAD_MANIFEST, {}).error).toMatchObject({ reason: 'OUTPUT_NOT_JSON', cliExitCode: 0 });
        expect(mapTeaRun(run({ code: 0, stdout: MANIFEST }), TeaDocumentKind.DOWNLOAD_MANIFEST, {})).toMatchObject({ outcome: 'OK', error: null });
    });

    it('every exit code of the table maps to its outcome, the real code deciding even against the object', () => {
        const table: Record<number, string> = { 1: 'FAILED', 2: 'INVALID_REQUEST', 3: 'NOT_FOUND', 5: 'URL_REFUSED', 6: 'AUTH', 8: 'ADDRESS_REFUSED', 9: 'LIMIT', 10: 'CONNECT_FAILED' };
        for (const [code, outcome] of Object.entries(table)) {
            const mapped = mapTeaRun(run({ code: Number(code), stderr: errorLine(Number(code), { reason: 'THE_REASON' }) }), TeaDocumentKind.PRODUCT, {});
            expect(mapped.outcome, code).toBe(outcome);
            expect(mapped.error, code).toMatchObject({ reason: 'THE_REASON', cliExitCode: Number(code), message: SENTENCES[outcome], detail: 'm' });
            expect(mapped.line).toBeUndefined();
            // an object that says another code is not used: the reason is the fixed one
            const wrong = mapTeaRun(run({ code: Number(code), stderr: errorLine(99, { reason: 'THE_REASON' }) }), TeaDocumentKind.PRODUCT, {});
            expect(wrong.outcome, code).toBe(outcome);
            expect(wrong.error, code).toMatchObject({ reason: 'NO_ERROR_OBJECT', cliExitCode: Number(code), detail: null });
        }
    });

    it('an exit code outside the table, exit 4 included, is FAILED with the reason EXIT_CODE and the object\'s fields', () => {
        for (const code of [4, 11, 255, -1]) {
            const mapped = mapTeaRun(run({ code, stderr: errorLine(code, { reason: 'THEIRS', message: 'their text', url: 'https://x.example.com/p' }) }), TeaDocumentKind.PRODUCT, {});
            expect(mapped.outcome, String(code)).toBe('FAILED');
            expect(mapped.error, String(code)).toMatchObject({ reason: 'EXIT_CODE', cliExitCode: code, detail: 'their text', url: 'https://x.example.com/p' });
        }
        expect(mapTeaRun(run({ code: 4 }), TeaDocumentKind.PRODUCT, {}).error).toMatchObject({ reason: 'EXIT_CODE', detail: null, url: null });
    });

    it('copies the object\'s fields one by one, cut at their bounds and made printable, and takes nothing else', () => {
        const stderr = errorLine(8, {
            reason: 'R'.repeat(80), message: 'm'.repeat(3000), url: 'u'.repeat(3000), httpStatus: 503, address: 'a'.repeat(300),
            addressClass: 'c'.repeat(80), phase: 'p'.repeat(80), hint: 'h'.repeat(3000), ignoredProxyVariables: ['HTTP_PROXY'], extra: 'never'
        });
        const error = mapTeaRun(run({ code: 8, stderr }), TeaDocumentKind.PRODUCT, {}).error!;
        expect(error).toEqual({
            reason: 'R'.repeat(64), cliExitCode: 8, message: SENTENCES.ADDRESS_REFUSED, detail: 'm'.repeat(2048), url: 'u'.repeat(2048),
            httpStatus: 503, address: 'a'.repeat(256), addressClass: 'c'.repeat(64), phase: 'p'.repeat(64), hint: 'h'.repeat(2048),
            ignoredProxyVariables: ['HTTP_PROXY']
        });
        const printable = mapTeaRun(run({ code: 8, stderr: errorLine(8, { reason: 'a\u00e9b', message: 'x\ty', hint: '\u202e' }) }), TeaDocumentKind.PRODUCT, {}).error!;
        expect(printable).toMatchObject({ reason: 'a?b', detail: 'x?y', hint: '?' });
    });

    it('a field that is not of its type is null, the reason stays the fixed one when it is empty or not text, and httpStatus is an integer', () => {
        const error = mapTeaRun(run({
            code: 8, stderr: errorLine(8, { reason: '', message: 5, url: {}, httpStatus: '503', address: null, addressClass: 7, phase: [], hint: true })
        }), TeaDocumentKind.PRODUCT, {}).error!;
        expect(error).toMatchObject({ reason: 'NO_ERROR_OBJECT', detail: null, url: null, httpStatus: null, address: null, addressClass: null, phase: null, hint: null });
        for (const httpStatus of [5.5, null, 'x', true, undefined]) {
            expect(mapTeaRun(run({ code: 8, stderr: errorLine(8, { httpStatus }) }), TeaDocumentKind.PRODUCT, {}).error!.httpStatus, String(httpStatus)).toBeNull();
        }
        expect(mapTeaRun(run({ code: 8, stderr: errorLine(8, { httpStatus: 0 }) }), TeaDocumentKind.PRODUCT, {}).error!.httpStatus).toBe(0);
        expect(mapTeaRun(run({ code: 8, stderr: errorLine(8, { reason: 7 }) }), TeaDocumentKind.PRODUCT, {}).error!.reason).toBe('NO_ERROR_OBJECT');
    });

    it('a connect failure names the proxy variables of the object and of rebom\'s own environment, once each', () => {
        const stderr = errorLine(10, { ignoredProxyVariables: ['HTTPS_PROXY', 5, null, 'x'.repeat(80), 'a\u00e9'] });
        const env = { HTTP_PROXY: 'p', HTTPS_PROXY: 'p', no_proxy: '' };
        const error = mapTeaRun(run({ code: 10, stderr }), TeaDocumentKind.PRODUCT, env).error!;
        expect(error.ignoredProxyVariables).toEqual(['HTTPS_PROXY', 'x'.repeat(64), 'a?', 'HTTP_PROXY', 'no_proxy']);
        expect(mapTeaRun(run({ code: 10 }), TeaDocumentKind.PRODUCT, {}).error!.ignoredProxyVariables).toEqual([]);
        expect(mapTeaRun(run({ code: 10 }), TeaDocumentKind.PRODUCT, { ALL_PROXY: 'p' }).error!.ignoredProxyVariables).toEqual(['ALL_PROXY']);
        // any other exit leaves the list as the object had it
        expect(mapTeaRun(run({ code: 8, stderr: errorLine(8) }), TeaDocumentKind.PRODUCT, { HTTP_PROXY: 'p' }).error!.ignoredProxyVariables).toBeNull();
        expect(mapTeaRun(run({ code: 8, stderr: errorLine(8, { ignoredProxyVariables: ['A', 2] }) }), TeaDocumentKind.PRODUCT, { HTTP_PROXY: 'p' }).error!.ignoredProxyVariables).toEqual(['A']);
        expect(mapTeaRun(run({ code: 8, stderr: errorLine(8, { ignoredProxyVariables: 'HTTP_PROXY' }) }), TeaDocumentKind.PRODUCT, {}).error!.ignoredProxyVariables).toBeNull();
    });
});
