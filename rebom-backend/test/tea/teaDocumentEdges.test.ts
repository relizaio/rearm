import * as fs from 'fs';
import * as path from 'path';
import { afterEach, describe, expect, it } from 'vitest';
import {
    checkTeaManifest, documentClassOf, parseTeaDocument, printableAscii, teaFileName, teaFilePath, TeaDocumentKind
} from '../../src/services/tea/teaDocument';
import { cleanupDirs, tempDir } from './teaTestKit';

/**
 * The document line and the download manifest as rebom checks them (design 3.3, 3.6.1 step 5),
 * at every condition that refuses.
 */

afterEach(cleanupDirs);

const PRODUCT = TeaDocumentKind.PRODUCT;
const LINE = '{"formatVersion":1,"kind":"TEA_PRODUCT","product":{"name":"a b"}}';

describe('the document line', () => {
    it('one line with or without its final line feed is accepted and returned as written', () => {
        for (const stdout of [LINE, `${LINE}\n`]) {
            const parsed = parseTeaDocument(stdout, PRODUCT);
            expect(parsed).toEqual({ ok: true, line: LINE, doc: { formatVersion: 1, kind: 'TEA_PRODUCT', product: { name: 'a b' } } });
        }
    });

    it('nothing, a lone line feed, several lines, an empty line after the document and a split line are not JSON', () => {
        for (const stdout of ['', '\n', `${LINE}\n${LINE}\n`, `${LINE}\n${LINE}`, `${LINE}\n\n`, '\n' + LINE, '{"formatVersion":1,\n"kind":"TEA_PRODUCT"}']) {
            expect(parseTeaDocument(stdout, PRODUCT), JSON.stringify(stdout)).toEqual({ ok: false, reason: 'OUTPUT_NOT_JSON' });
        }
    });

    it('text that is not JSON, and JSON that is not an object, are not JSON', () => {
        for (const stdout of ['not json', '{"a":', '[1]', 'null', '5', '"x"', 'true', '[{"formatVersion":1,"kind":"TEA_PRODUCT"}]']) {
            expect(parseTeaDocument(stdout, PRODUCT), stdout).toEqual({ ok: false, reason: 'OUTPUT_NOT_JSON' });
        }
    });

    it('the format version must be the number 1, and is looked at before the kind', () => {
        for (const version of ['2', '"1"', '1.5', 'null', '[1]', '0', 'true']) {
            expect(parseTeaDocument(`{"formatVersion":${version},"kind":"TEA_PRODUCT"}`, PRODUCT), version).toEqual({ ok: false, reason: 'FORMAT_VERSION' });
        }
        expect(parseTeaDocument('{"kind":"TEA_PRODUCT"}', PRODUCT)).toEqual({ ok: false, reason: 'FORMAT_VERSION' });
        expect(parseTeaDocument('{"formatVersion":2,"kind":"OTHER"}', PRODUCT)).toEqual({ ok: false, reason: 'FORMAT_VERSION' });
    });

    it('the kind must be the one the verb expects', () => {
        expect(parseTeaDocument(LINE, TeaDocumentKind.COMPONENT)).toEqual({ ok: false, reason: 'KIND' });
        expect(parseTeaDocument('{"formatVersion":1}', PRODUCT)).toEqual({ ok: false, reason: 'KIND' });
        expect(parseTeaDocument('{"formatVersion":1,"kind":"tea_product"}', PRODUCT)).toEqual({ ok: false, reason: 'KIND' });
    });
});

describe('classes, names and printable text', () => {
    it('BOM is an SBOM, VULNERABILITIES a VEX, anything else OTHER', () => {
        expect(documentClassOf('BOM')).toBe('SBOM');
        expect(documentClassOf('VULNERABILITIES')).toBe('VEX');
        for (const value of ['bom', 'Bom', 'LICENSE', 'FUTURE_TYPE', '', undefined, null, 5, {}]) expect(documentClassOf(value), String(value)).toBe('OTHER');
    });

    it('a file is named by its number as six digits and .bin, and its path joins the directory', () => {
        expect(teaFileName(1)).toBe('000001.bin');
        expect(teaFileName(123456)).toBe('123456.bin');
        expect(teaFileName(1234567)).toBe('1234567.bin');
        expect(teaFilePath('/scratch/dl', 7)).toBe('/scratch/dl/000007.bin');
    });

    it('printable ASCII stays, anything else is one question mark per character, and the cut is at the bound', () => {
        expect(printableAscii(' !~abc', 100)).toBe(' !~abc');
        expect(printableAscii('a\x1fb\x7fc\x00d\ne', 100)).toBe('a?b?c?d?e');
        expect(printableAscii('caf\u00e9', 100)).toBe('caf?');
        expect(printableAscii('x\u{1F600}y', 100)).toBe('x?y');
        expect(printableAscii('abcdef', 3)).toBe('abc');
        expect(printableAscii('abc', 3)).toBe('abc');
        expect(printableAscii('abc', 4)).toBe('abc');
        expect(printableAscii('a\u00e9\u00e9\u00e9', 3)).toBe('a??');
        expect(printableAscii('abc', 0)).toBe('');
        expect(printableAscii('', 5)).toBe('');
    });
});

/** A download directory with the given files, and the manifest document for it. */
function download(files: Record<string, string | { dir: true } | { link: string }>): string {
    const dir = path.join(tempDir('rebom-manifest-'), 'dl');
    fs.mkdirSync(dir);
    for (const [name, content] of Object.entries(files)) {
        const target = path.join(dir, name);
        if (typeof content === 'string') fs.writeFileSync(target, content);
        else if ('dir' in content) fs.mkdirSync(target);
        else fs.symlinkSync(content.link, target);
    }
    return dir;
}

function row(number: number, extra: Record<string, unknown> = {}): Record<string, unknown> {
    return { number, status: 'VERIFIED', fileName: teaFileName(number), sizeBytes: 3, artifactType: 'BOM', reason: null, ...extra };
}

describe('the manifest against the directory', () => {
    it('accepts verified rows whose files are there, and keeps number, status, reason, class and size', async () => {
        const dir = download({ '000001.bin': 'abc', '000003.bin': 'abc' });
        const rows = await checkTeaManifest({
            files: [
                row(1),
                { number: 2, status: 'FAILED', reason: 'CHECKSUM_MISMATCH', artifactType: 'VULNERABILITIES' },
                row(3, { artifactType: 'LICENSE' }),
                { number: 4, status: 'NOT_ATTEMPTED', reason: 'TOTAL_BYTES' },
                { number: 5, status: 'NOT_SELECTED' }
            ]
        }, dir, 1000);
        expect(rows).toEqual([
            { number: 1, status: 'VERIFIED', reason: null, documentClass: 'SBOM', sizeBytes: 3 },
            { number: 2, status: 'FAILED', reason: 'CHECKSUM_MISMATCH', documentClass: 'VEX', sizeBytes: null },
            { number: 3, status: 'VERIFIED', reason: null, documentClass: 'OTHER', sizeBytes: 3 },
            { number: 4, status: 'NOT_ATTEMPTED', reason: 'TOTAL_BYTES', documentClass: 'OTHER', sizeBytes: null },
            { number: 5, status: 'NOT_SELECTED', reason: null, documentClass: 'OTHER', sizeBytes: null }
        ]);
    });

    it('refuses a files member that is not a list, and a row that is not an object', async () => {
        const dir = download({});
        for (const files of [undefined, null, {}, 'x', 5]) expect(await checkTeaManifest({ files }, dir, 10), JSON.stringify(files)).toBeNull();
        for (const bad of [null, 'x', 5, [], [1]]) expect(await checkTeaManifest({ files: [bad] }, dir, 10), JSON.stringify(bad)).toBeNull();
        expect(await checkTeaManifest({ files: [] }, dir, 10)).toEqual([]);
    });

    it('refuses a number that is not an integer, is not above the previous one, or repeats', async () => {
        const dir = download({});
        const status = (number: unknown) => ({ number, status: 'NOT_SELECTED' });
        for (const numbers of [['1'], [1.5], [0], [-1], [null], [1, 1], [2, 1], [1, 3, 2], [NaN]]) {
            expect(await checkTeaManifest({ files: numbers.map(status) }, dir, 10), JSON.stringify(numbers)).toBeNull();
        }
        expect(await checkTeaManifest({ files: [1, 3, 10].map(status) }, dir, 10)).toHaveLength(3);
    });

    it('refuses a status that is not one of the four', async () => {
        const dir = download({});
        for (const status of [undefined, null, 'DONE', 'verified', 5, '']) {
            expect(await checkTeaManifest({ files: [{ number: 1, status }] }, dir, 10), String(status)).toBeNull();
        }
        for (const status of ['FAILED', 'NOT_ATTEMPTED', 'NOT_SELECTED']) {
            expect(await checkTeaManifest({ files: [{ number: 1, status }] }, dir, 10), status).toHaveLength(1);
        }
    });

    it('refuses a verified row whose file name is not the number\'s', async () => {
        const dir = download({ '000001.bin': 'abc', '000002.bin': 'abc' });
        for (const fileName of ['000002.bin', '1.bin', '../000001.bin', undefined, null, 5, '000001.bin ']) {
            expect(await checkTeaManifest({ files: [row(1, { fileName })] }, dir, 1000), String(fileName)).toBeNull();
        }
    });

    it('refuses a verified size that is not an integer from 0 to the artifact bound, or not the file\'s', async () => {
        const empty = download({ '000001.bin': '' });
        expect(await checkTeaManifest({ files: [row(1, { sizeBytes: 0 })] }, empty, 10)).toHaveLength(1);
        for (const sizeBytes of [-1, 1, 1.5, '0', null, undefined, NaN]) {
            expect(await checkTeaManifest({ files: [row(1, { sizeBytes })] }, empty, 10), String(sizeBytes)).toBeNull();
        }
        const three = download({ '000001.bin': 'abc' });
        expect(await checkTeaManifest({ files: [row(1, { sizeBytes: 3 })] }, three, 3)).toHaveLength(1);
        expect(await checkTeaManifest({ files: [row(1, { sizeBytes: 3 })] }, three, 2)).toBeNull();
        expect(await checkTeaManifest({ files: [row(1, { sizeBytes: 4 })] }, three, 10)).toBeNull();
        expect(await checkTeaManifest({ files: [row(1, { sizeBytes: 2 })] }, three, 10)).toBeNull();
        const over = download({ '000001.bin': 'abcdef' });
        expect(await checkTeaManifest({ files: [row(1, { sizeBytes: 6 })] }, over, 5)).toBeNull();
    });

    it('refuses a verified row whose file is missing, a directory or a link', async () => {
        const missing = download({});
        expect(await checkTeaManifest({ files: [row(1)] }, missing, 10)).toBeNull();
        const asDir = download({ '000001.bin': { dir: true } });
        expect(await checkTeaManifest({ files: [row(1, { sizeBytes: 0 })] }, asDir, 10)).toBeNull();
        const target = path.join(tempDir('rebom-target-'), 'real');
        fs.writeFileSync(target, 'abc');
        const link = download({ '000001.bin': { link: target } });
        expect(await checkTeaManifest({ files: [row(1)] }, link, 10)).toBeNull();
    });

    it('refuses a directory that holds anything the manifest does not account for', async () => {
        const extra = download({ '000001.bin': 'abc', 'stray.txt': 'x' });
        expect(await checkTeaManifest({ files: [row(1)] }, extra, 10)).toBeNull();
        const hidden = download({ '000001.bin': 'abc', '.part': '' });
        expect(await checkTeaManifest({ files: [row(1)] }, hidden, 10)).toBeNull();
        const unlisted = download({ '000001.bin': 'abc', '000002.bin': 'abc' });
        expect(await checkTeaManifest({ files: [row(1)] }, unlisted, 10)).toBeNull();
        const failedRowFile = download({ '000001.bin': 'abc' });
        expect(await checkTeaManifest({ files: [{ number: 1, status: 'FAILED' }] }, failedRowFile, 10)).toBeNull();
        const empty = download({});
        expect(await checkTeaManifest({ files: [{ number: 1, status: 'FAILED' }] }, empty, 10)).toHaveLength(1);
    });

    it('a directory that is missing is valid when no file was kept, and a directory that is a file is not', async () => {
        const gone = path.join(tempDir('rebom-gone-'), 'dl');
        expect(await checkTeaManifest({ files: [{ number: 1, status: 'NOT_SELECTED' }] }, gone, 10)).toHaveLength(1);
        expect(await checkTeaManifest({ files: [] }, gone, 10)).toEqual([]);
        expect(await checkTeaManifest({ files: [row(1)] }, gone, 10)).toBeNull();
        const file = path.join(tempDir('rebom-file-'), 'dl');
        fs.writeFileSync(file, 'x');
        expect(await checkTeaManifest({ files: [{ number: 1, status: 'NOT_SELECTED' }] }, file, 10)).toBeNull();
        expect(await checkTeaManifest({ files: [] }, file, 10)).toBeNull();
    });

    it('keeps a row\'s reason as printable text of at most 64 bytes, and nothing when it is not a string', async () => {
        const dir = download({});
        const reasonOf = async (reason: unknown) => (await checkTeaManifest({ files: [{ number: 1, status: 'FAILED', reason }] }, dir, 10))![0].reason;
        expect(await reasonOf('R'.repeat(64))).toBe('R'.repeat(64));
        expect(await reasonOf('R'.repeat(65))).toBe('R'.repeat(64));
        expect(await reasonOf('a\u00e9\nb')).toBe('a??b');
        for (const reason of [null, undefined, 5, {}, ['x']]) expect(await reasonOf(reason), String(reason)).toBeNull();
    });
});
