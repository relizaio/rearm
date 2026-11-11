import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { logger } from '../../src/logger';
import { requireTeaCaller, setTeaCallerToken, teaContextOf, TEA_CALLER_HEADER } from '../../src/services/tea/teaCallerGate';

/**
 * The caller gate on its own (design 3.2): which caller passes, what a refusal answers, and the one
 * warn line at most every ten seconds with the number of refusals since the last line.
 */

const TOKEN = 'a'.repeat(40);
let warn: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-01-01T00:00:00.000Z'));
    warn = vi.spyOn(logger, 'warn').mockImplementation(() => undefined as never);
    setTeaCallerToken(TOKEN);
});

afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    setTeaCallerToken(null);
});

const advance = (ms: number) => vi.setSystemTime(new Date(Date.now() + ms));
const refuse = () => requireTeaCaller({ teaCallerToken: 'wrong' });

describe('who passes', () => {
    it('only the exact token passes; no token configured is NOT_CONFIGURED for everyone, the right header included', () => {
        expect(requireTeaCaller({ teaCallerToken: TOKEN })).toBeNull();
        setTeaCallerToken(null);
        for (const context of [{ teaCallerToken: TOKEN }, {}, undefined]) {
            expect(requireTeaCaller(context)).toMatchObject({ outcome: 'NOT_CONFIGURED', error: { reason: 'CALLER_TOKEN' } });
        }
        expect(warn).not.toHaveBeenCalled();
    });

    it('a wrong, absent, empty or non-text header is one answer: CALLER_REFUSED with the reason CALLER', () => {
        for (const context of [{ teaCallerToken: 'b'.repeat(40) }, { teaCallerToken: TOKEN + 'a' }, { teaCallerToken: TOKEN.slice(1) }, { teaCallerToken: '' },
            {}, undefined, { teaCallerToken: 5 as any }, { teaCallerToken: [TOKEN] as any }, { teaCallerToken: null as any }]) {
            const result = requireTeaCaller(context);
            expect(result, JSON.stringify(context)).toMatchObject({ teaApiVersion: 1, outcome: 'CALLER_REFUSED', error: { reason: 'CALLER' } });
        }
    });

    it('the context copies the header\'s text and nothing else', () => {
        expect(TEA_CALLER_HEADER).toBe('x-rebom-caller-token');
        expect(teaContextOf({ 'x-rebom-caller-token': TOKEN, authorization: 'Bearer x', cookie: 'c' })).toEqual({ teaCallerToken: TOKEN });
        expect(teaContextOf({ 'x-rebom-caller-token': '' })).toEqual({ teaCallerToken: '' });
        expect(teaContextOf({ 'x-rebom-caller-token': [TOKEN, TOKEN] })).toEqual({});
        expect(teaContextOf({ 'x-rebom-caller-token': undefined })).toEqual({});
        expect(teaContextOf({ 'X-Rebom-Caller-Token': TOKEN })).toEqual({});
        expect(teaContextOf({})).toEqual({});
    });
});

describe('the warn line of the refusals', () => {
    it('the first refusal is logged at once, alone, with a count of one and nothing of the presented value', () => {
        refuse();
        expect(warn).toHaveBeenCalledTimes(1);
        expect(warn.mock.calls[0]).toEqual([{ refusals: 1 }, 'TEA caller refused']);
        requireTeaCaller({ teaCallerToken: 'presented-secret-value' });
        expect(JSON.stringify(warn.mock.calls)).not.toContain('presented-secret-value');
    });

    it('counts the refusals inside ten seconds and logs them at the instant ten seconds have passed, not before', () => {
        refuse();
        for (let i = 0; i < 4; i++) refuse();
        expect(warn).toHaveBeenCalledTimes(1);
        advance(9_999);
        refuse();
        expect(warn).toHaveBeenCalledTimes(1);
        advance(1);
        refuse();
        expect(warn).toHaveBeenCalledTimes(2);
        expect(warn.mock.calls[1]).toEqual([{ refusals: 6 }, 'TEA caller refused']);
        // the count starts again after a line
        refuse();
        refuse();
        advance(10_000);
        refuse();
        expect(warn).toHaveBeenCalledTimes(3);
        expect(warn.mock.calls[2][0]).toEqual({ refusals: 3 });
        advance(10_001);
        refuse();
        expect(warn.mock.calls[3][0]).toEqual({ refusals: 1 });
    });

    it('a pass does not count and does not log; configuring again starts the count and the clock over', () => {
        refuse();
        requireTeaCaller({ teaCallerToken: TOKEN });
        requireTeaCaller({ teaCallerToken: TOKEN });
        refuse();
        expect(warn).toHaveBeenCalledTimes(1);
        setTeaCallerToken(TOKEN);
        refuse();
        expect(warn).toHaveBeenCalledTimes(2);
        expect(warn.mock.calls[1][0]).toEqual({ refusals: 1 });
        refuse();
        setTeaCallerToken(TOKEN);
        refuse();
        expect(warn.mock.calls[2][0]).toEqual({ refusals: 1 });
    });
});
