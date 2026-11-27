import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { Writable } from 'stream';
import { resolveLogLevel, LOG_LEVELS, UNKNOWN_LOG_LEVEL_LINE } from '../../src/logger';

const NODE_ENVS: (string | undefined)[] = [undefined, 'production', 'development'];
const LEVEL_NAMES = ['trace', 'debug', 'info', 'warn', 'error', 'fatal', 'silent'];

describe('resolveLogLevel', () => {
  it('lists the seven pino level names and nothing else', () => {
    expect([...LOG_LEVELS]).toEqual(LEVEL_NAMES);
  });

  it('gives info when LOG_LEVEL is not set, and debug only under NODE_ENV development', () => {
    for (const logLevel of [undefined, '', '   ', '\n', '\t ']) {
      for (const nodeEnv of NODE_ENVS) {
        const choice = resolveLogLevel({ LOG_LEVEL: logLevel, NODE_ENV: nodeEnv });
        expect(choice, `LOG_LEVEL ${JSON.stringify(logLevel)} NODE_ENV ${nodeEnv}`).toEqual({
          level: nodeEnv === 'development' ? 'debug' : 'info',
          unknownValue: false,
        });
      }
    }
  });

  it('gives the named level whatever NODE_ENV is', () => {
    for (const name of LEVEL_NAMES) {
      for (const nodeEnv of NODE_ENVS) {
        expect(resolveLogLevel({ LOG_LEVEL: name, NODE_ENV: nodeEnv }), `${name} under ${nodeEnv}`)
          .toEqual({ level: name, unknownValue: false });
      }
    }
  });

  it('gives warn for warn under development, which the old precedence turned into debug', () => {
    expect(resolveLogLevel({ LOG_LEVEL: 'warn', NODE_ENV: 'development' })).toEqual({ level: 'warn', unknownValue: false });
  });

  it('matches the name in any letter case and with surrounding whitespace', () => {
    const cases: [string, string][] = [
      ['WARN', 'warn'],
      ['Error', 'error'],
      [' warn', 'warn'],
      ['warn\n', 'warn'],
      ['\tSILENT ', 'silent'],
    ];
    for (const [given, expected] of cases) {
      expect(resolveLogLevel({ LOG_LEVEL: given }), JSON.stringify(given)).toEqual({ level: expected, unknownValue: false });
    }
  });

  it('gives info with unknownValue for a value that is not a level, also under development', () => {
    const values = ['verbose', 'warning', '40', 'w arn', 'constructor', 'toString', '__proto__', 'hasOwnProperty'];
    for (const value of values) {
      for (const nodeEnv of [undefined, 'development']) {
        expect(resolveLogLevel({ LOG_LEVEL: value, NODE_ENV: nodeEnv }), `${value} under ${nodeEnv}`)
          .toEqual({ level: 'info', unknownValue: true });
      }
    }
  });
});

describe('the logger built at module load', () => {
  const KEYS = ['LOG_LEVEL', 'NODE_ENV', 'LOG_TYPE'] as const;
  const saved: Record<string, string | undefined> = {};
  let lines: any[];

  const sink = new Writable({
    write(chunk, _encoding, callback) {
      for (const text of chunk.toString().split('\n')) {
        if (text.trim() !== '') lines.push(JSON.parse(text));
      }
      callback();
    },
  });

  function setEnv(values: Partial<Record<typeof KEYS[number], string>>) {
    for (const key of KEYS) {
      if (values[key] === undefined) delete process.env[key]; else process.env[key] = values[key];
    }
  }

  async function loadLogger() {
    vi.resetModules();
    vi.doMock('pino-pretty', () => ({ default: () => sink }));
    return (await import('../../src/logger')).logger;
  }

  beforeEach(() => {
    lines = [];
    for (const key of KEYS) saved[key] = process.env[key];
  });

  afterEach(() => {
    for (const key of KEYS) {
      if (saved[key] === undefined) delete process.env[key]; else process.env[key] = saved[key];
    }
    vi.doUnmock('pino-pretty');
    vi.resetModules();
  });

  it('runs at warn for LOG_LEVEL warn under development: info and debug write nothing, warn and error write', async () => {
    setEnv({ LOG_LEVEL: 'warn', NODE_ENV: 'development' });
    const logger = await loadLogger();
    expect(lines).toHaveLength(0);
    expect(logger.level).toBe('warn');
    logger.info('an info line');
    logger.debug('a debug line');
    expect(lines).toHaveLength(0);
    logger.warn('a warn line');
    logger.error('an error line');
    expect(lines.map((l) => [l.level, l.msg])).toEqual([[40, 'a warn line'], [50, 'an error line']]);
  });

  it('runs at info with neither LOG_LEVEL nor NODE_ENV', async () => {
    setEnv({});
    const logger = await loadLogger();
    expect(logger.level).toBe('info');
    expect(lines).toHaveLength(0);
  });

  it('runs at debug with only NODE_ENV development', async () => {
    setEnv({ NODE_ENV: 'development' });
    const logger = await loadLogger();
    expect(logger.level).toBe('debug');
    expect(lines).toHaveLength(0);
  });

  it('gives the level to the json logger too: LOG_TYPE json with LOG_LEVEL ERROR runs at error', async () => {
    setEnv({ LOG_TYPE: 'json', LOG_LEVEL: 'ERROR' });
    const logger = await loadLogger();
    expect(logger.level).toBe('error');
    expect(logger.isLevelEnabled('warn')).toBe(false);
    expect(logger.isLevelEnabled('error')).toBe(true);
  });

  for (const value of ['verbose', 'constructor']) {
    it(`starts at info with one constant warn line for LOG_LEVEL ${value}`, async () => {
      setEnv({ LOG_LEVEL: value });
      const logger = await loadLogger();
      expect(lines).toHaveLength(1);
      expect(lines[0].level).toBe(40);
      expect(lines[0].msg).toBe(UNKNOWN_LOG_LEVEL_LINE);
      expect(JSON.stringify(lines[0])).not.toContain(value);
      expect(logger.level).toBe('info');
      logger.info('after the warning');
      expect(lines).toHaveLength(2);
      expect(lines[1].msg).toBe('after the warning');
    });
  }
});
