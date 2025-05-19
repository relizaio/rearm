import pino from 'pino';
import pinoPretty from 'pino-pretty';

export let logger = pino(
  pinoPretty({
    colorize: true,
    translateTime: 'yyyy-mm-dd HH:MM:ss.l',
    ignore: 'pid,hostname'
  })
);

export type Logger = typeof logger;