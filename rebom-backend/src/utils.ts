const pg = require('pg')
const { spawn } = require('node:child_process')
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { logger } from './logger';

// init db
const pool = new pg.Pool({
    user: process.env.POSTGRES_USER ? process.env.POSTGRES_USER : `postgres`,
    host: process.env.POSTGRES_HOST ? process.env.POSTGRES_HOST : `localhost`,
    database: process.env.POSTGRES_DATABASE ? process.env.POSTGRES_DATABASE : `postgres`,
    password: process.env.POSTGRES_PASSWORD ? process.env.POSTGRES_PASSWORD : `password`,
    port: process.env.POSTGRES_PORT ? parseInt(process.env.POSTGRES_PORT) : 5438,
})

export function getPool() {
    return pool
}

export async function runQuery (query: string, params: string[]) : Promise<any> {
    const client = await pool.connect()
    try {
      return await client.query(query, params)
    
    } catch (error) {
      logger.error("Error running query: ", error)
    }
     finally {
      // Make sure to release the client before any error handling,
      // just in case the error handling itself throws an error.
      client.release()
    }
}

export async function shellExec(cmd: string, args: any[], timeout?: number): Promise<string> { // timeout = in ms
  return new Promise((resolve, reject) => {
      let options: any = {}
      if (timeout) options.timeout = timeout
      const child = spawn(cmd, args, options)
      let resData = ""
      child.stdout.on('data', (data: string)=> {
          resData += data
      })
    
      child.stderr.on('data', (data: any) => {
          logger.error(`shell command error: ${data}`)
      })

      child.on('exit', (code: number) => {
          if (code !== 0) logger.error(`shell process exited with code ${code}`)
          if (code === 0) {
              if (resData) {
                  resData = resData.replace(/\n$/, "")
              }
              resolve(resData)
          } else {
              logger.error(resData)
              reject(resData)
          }
      })
  })
}

export async function createTmpFiles(dataArr: any[]): Promise<string[]> {
    const tmpDir = os.tmpdir();
    const filePaths: string[] = [];

    for (const data of dataArr) {
        const tmpFilePath = path.join(tmpDir, `${Date.now()}-${Math.random().toString(36).substring(2, 15)}`);
        await fs.promises.writeFile(tmpFilePath, JSON.stringify(data));
        filePaths.push(tmpFilePath);
    }

    return filePaths;
}

export async function deleteTmpFiles(filePaths: string[]): Promise<void> {
    for (const filePath of filePaths) {
        await fs.promises.unlink(filePath);
    }
}

export async function writeFileAsync(filename: string, content: string): Promise<void> {
    try {
      await fs.promises.writeFile(filename, content);
      console.log(`File ${filename} has been written successfully`);
    } catch (error) {
      console.error(`Error writing to file ${filename}:`, error);
    }
  }
  