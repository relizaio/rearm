import pino from 'pino';
import pinoPretty from 'pino-pretty';

// The levels LOG_LEVEL may name. The value is checked against this list before
// pino sees it: pino throws on an unknown level and accepts some names that are
// not levels (constructor, toString) as a level that prints nothing.
export const LOG_LEVELS: readonly pino.LevelWithSilent[] = ['trace', 'debug', 'info', 'warn', 'error', 'fatal', 'silent'];

// Logged once at start when LOG_LEVEL is not one of LOG_LEVELS. A constant: the
// value itself is never logged.
export const UNKNOWN_LOG_LEVEL_LINE = 'LOG_LEVEL is set to a value that is not a log level; using info. Log levels: trace, debug, info, warn, error, fatal, silent.';

export interface LogLevelChoice {
  level: pino.LevelWithSilent;
  unknownValue: boolean;
}

// LOG_LEVEL, trimmed and in any letter case, is the level. Absent, empty or
// whitespace only: debug when NODE_ENV is development, else info. Any other
// value: info, also under development, and unknownValue is true.
export function resolveLogLevel(env: { LOG_LEVEL?: string; NODE_ENV?: string }): LogLevelChoice {
  const given = (env.LOG_LEVEL ?? '').trim().toLowerCase();
  if (given === '') {
    return { level: env.NODE_ENV === 'development' ? 'debug' : 'info', unknownValue: false };
  }
  const known = LOG_LEVELS.find((l) => l === given);
  if (known === undefined) {
    return { level: 'info', unknownValue: true };
  }
  return { level: known, unknownValue: false };
}

const logLevelChoice = resolveLogLevel(process.env);
const logLevel = logLevelChoice.level;
const logType = process.env.LOG_TYPE || 'plain';

export let logger = logType === 'json'
  ? pino({
      level: logLevel,
      formatters: {
        level: (label) => {
          return { level: label };
        }
      },
      timestamp: pino.stdTimeFunctions.isoTime
    })
  : pino(
      {
        level: logLevel
      },
      pinoPretty({
        colorize: true,
        translateTime: 'yyyy-mm-dd HH:MM:ss.l',
        ignore: 'pid,hostname'
      })
    );

if (logLevelChoice.unknownValue) {
  logger.warn(UNKNOWN_LOG_LEVEL_LINE);
}

export type Logger = typeof logger;
