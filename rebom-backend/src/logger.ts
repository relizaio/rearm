import pino from 'pino';
import pinoPretty from 'pino-pretty';

const logLevel = process.env.LOG_LEVEL || process.env.NODE_ENV === 'development' ? 'debug' : 'info';

export let logger = pino(
  {
    level: logLevel
  },
  pinoPretty({
    colorize: true,
    translateTime: 'yyyy-mm-dd HH:MM:ss.l',
    ignore: 'pid,hostname'
  })
);

export type Logger = typeof logger;