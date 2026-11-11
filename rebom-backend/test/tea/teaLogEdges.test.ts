import { afterEach, describe, expect, it, vi } from 'vitest';
import { logger } from '../../src/logger';
import { hostOf, logTeaEvent } from '../../src/services/tea/teaLog';

/**
 * The one log function of the TEA operations (design 3.10): a fixed list of fields, nothing passed
 * on that is null or absent, nothing else copied, and the host of an API root alone.
 */

afterEach(() => {
    vi.restoreAllMocks();
});

function logged(fields: Record<string, unknown>): { line: Record<string, unknown>, message: unknown, calls: number } {
    vi.restoreAllMocks();
    const info = vi.spyOn(logger, 'info').mockImplementation(() => undefined as never);
    logTeaEvent(fields as any);
    return { line: info.mock.calls[0]?.[0] as Record<string, unknown>, message: info.mock.calls[0]?.[1], calls: info.mock.calls.length };
}

describe('logTeaEvent', () => {
    it('writes one info line named "TEA operation" with every field of the list that has a value', () => {
        const all = {
            operation: 'teaProduct', outcome: 'OK', reason: 'R', correlationId: 'c-1', exitCode: 0, durationMs: 12, queueWaitMs: 3,
            stdoutBytes: 100, apiHost: 'tea.example.com', rowsByStatus: { VERIFIED: 2 }, heldBytes: 8192, org: 'org-1', bomUuid: 'u-1',
            stored: 'CREATED', ref: 'abcd1234'
        };
        const result = logged(all);
        expect(result.calls).toBe(1);
        expect(result.message).toBe('TEA operation');
        expect(result.line).toEqual(all);
    });

    it('leaves out a field that is undefined or null, and keeps zero, false and the empty string', () => {
        const result = logged({ operation: 'op', outcome: 'OK', reason: null, correlationId: undefined, exitCode: 0, durationMs: 0, stdoutBytes: 0, apiHost: '', stored: null, ref: undefined });
        expect(result.line).toEqual({ operation: 'op', outcome: 'OK', exitCode: 0, durationMs: 0, stdoutBytes: 0, apiHost: '' });
        expect(Object.keys(logged({ operation: 'op', outcome: 'OK' }).line)).toEqual(['operation', 'outcome']);
    });

    it('copies only the fields of the list, so an object with a secret in it never reaches the log', () => {
        const result = logged({ operation: 'op', outcome: 'OK', token: 'SECRET', credential: { token: 'SECRET' }, rebomOptions: { a: 1 }, stderr: 'SECRET' });
        expect(result.line).toEqual({ operation: 'op', outcome: 'OK' });
        expect(JSON.stringify(result.line)).not.toContain('SECRET');
    });
});

describe('hostOf', () => {
    it('is the host with its port and nothing of the path, the query or a user name', () => {
        expect(hostOf('https://tea.example.com/tea/v1/secret-path?token=x')).toBe('tea.example.com');
        expect(hostOf('https://user:password@Tea.Example.com:8443/tea')).toBe('tea.example.com:8443');
        expect(hostOf('http://127.0.0.1:8080/tea')).toBe('127.0.0.1:8080');
        expect(hostOf('https://[::1]:9443/tea')).toBe('[::1]:9443');
    });

    it('is null when there is no root or it does not parse', () => {
        expect(hostOf(undefined)).toBeNull();
        expect(hostOf('')).toBeNull();
        expect(hostOf('not a url')).toBeNull();
        expect(hostOf('https://')).toBeNull();
        expect(hostOf('/relative/path')).toBeNull();
    });
});
