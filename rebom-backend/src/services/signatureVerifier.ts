// Signature verification subprocess wrapper for the verifySignature
// GraphQL mutation. Two formats in v1:
//
//   SSH — ssh-keygen -Y verify, allowed_signers built from enrolled keys
//   GPG — gpg --verify into a transient keyring built from enrolled
//         ASCII-armoured pubkeys
//
// X.509 is not handled here in v1; rearm-backend short-circuits to
// ERRORED before this resolver is reached.
//
// Trust-store assembly happens in rearm-backend; this service is
// stateless w.r.t. enrolled keys — it accepts whatever wire-format
// trust store the caller passes in.

import { promises as fs } from 'fs'
import * as os from 'os'
import * as path from 'path'
import { spawn } from 'child_process'
import { logger } from '../logger'

export type VerifierVerdict = 'VERIFIED' | 'INVALID_SIGNATURE' | 'UNKNOWN_KEY' | 'ERRORED'

export interface VerifyResult {
  verdict: VerifierVerdict
  matchedFingerprint?: string | null
  details?: string | null
}

export interface VerifyInput {
  format: 'SSH' | 'GPG'
  signatureB64: string
  payloadB64: string
  trustStoreB64: string
  expectedIdentity?: string | null
}

export async function verifySignature (input: VerifyInput): Promise<VerifyResult> {
  switch (input.format) {
    case 'SSH':
      return verifySsh(input)
    case 'GPG':
      return verifyGpg(input)
    default:
      return { verdict: 'ERRORED', details: `Unknown format: ${input.format}` }
  }
}

// ssh-keygen -Y verify -f allowed_signers -I <identity> -n <namespace>
// -s <signature> < <payload>
//
// Reads the signature blob and payload from temp files. Identity in
// the allowed_signers must match the -I we pass — when the caller
// hasn't pinned an identity we sweep the allowed_signers and try
// each principal until one verifies (so a single endpoint serves
// the multi-key org case without the caller having to know which
// key is "the" key).
async function verifySsh (input: VerifyInput): Promise<VerifyResult> {
  const tmpDir = await fs.mkdtemp(path.join(os.tmpdir(), 'verify-ssh-'))
  try {
    const sigPath = path.join(tmpDir, 'signature.sig')
    const payloadPath = path.join(tmpDir, 'payload.bin')
    const allowedPath = path.join(tmpDir, 'allowed_signers')
    await fs.writeFile(sigPath, Buffer.from(input.signatureB64, 'base64'))
    await fs.writeFile(payloadPath, Buffer.from(input.payloadB64, 'base64'))
    await fs.writeFile(allowedPath, Buffer.from(input.trustStoreB64, 'base64'))

    const allowedSignersContent = (await fs.readFile(allowedPath, 'utf8')).trim()
    if (!allowedSignersContent) {
      return { verdict: 'UNKNOWN_KEY', details: 'Empty allowed_signers — no enrolled SSH keys for org' }
    }

    const identitiesToTry: string[] = input.expectedIdentity
      ? [input.expectedIdentity]
      : allowedSignersContent.split('\n')
          .map((l) => l.split(/\s+/)[0])
          .filter((s) => !!s && s !== '*')

    if (identitiesToTry.length === 0) {
      // Allowed_signers had wildcard principals only — ssh-keygen still needs
      // a concrete -I, so synthesize one (the verifier ignores it when the
      // entry uses *).
      identitiesToTry.push('placeholder@reliza')
    }

    let lastErr = 'no identity verified'
    for (const identity of identitiesToTry) {
      const res = await runSshKeygenYVerify({
        sigPath,
        payloadPath,
        allowedSignersPath: allowedPath,
        identity,
        namespace: 'git',
      })
      if (res.verdict === 'VERIFIED') {
        return res
      }
      lastErr = res.details ?? lastErr
    }
    return { verdict: 'UNKNOWN_KEY', details: lastErr }
  } catch (e: any) {
    logger.error({ err: e }, 'SSH verifier failed')
    return { verdict: 'ERRORED', details: e?.message ?? String(e) }
  } finally {
    await fs.rm(tmpDir, { recursive: true, force: true }).catch(() => null)
  }
}

interface SshArgs {
  sigPath: string
  payloadPath: string
  allowedSignersPath: string
  identity: string
  namespace: string
}

function runSshKeygenYVerify (args: SshArgs): Promise<VerifyResult> {
  return new Promise((resolve) => {
    const proc = spawn('ssh-keygen', [
      '-Y', 'verify',
      '-f', args.allowedSignersPath,
      '-I', args.identity,
      '-n', args.namespace,
      '-s', args.sigPath,
    ], { stdio: ['pipe', 'pipe', 'pipe'] })
    fs.readFile(args.payloadPath).then((buf) => {
      proc.stdin.write(buf)
      proc.stdin.end()
    }).catch((e) => proc.kill())
    let stdout = ''
    let stderr = ''
    proc.stdout.on('data', (chunk) => { stdout += chunk })
    proc.stderr.on('data', (chunk) => { stderr += chunk })
    proc.on('close', async (code) => {
      const combined = `${stdout}\n${stderr}`.trim()
      if (code === 0) {
        // Compute fingerprint of the matching key for the verdict. The
        // -Y verify output names the signer but not the fingerprint —
        // we re-derive by feeding the allowed_signers principal back
        // through `ssh-keygen -lf` against the line.
        const fingerprint = await pickFingerprintForIdentity(args.allowedSignersPath, args.identity)
        resolve({ verdict: 'VERIFIED', matchedFingerprint: fingerprint, details: combined })
        return
      }
      const text = combined.toLowerCase()
      if (text.includes('signature verification failed') || text.includes('bad signature')) {
        resolve({ verdict: 'INVALID_SIGNATURE', details: combined })
      } else if (text.includes('no principal matched') || text.includes('not authorized') || text.includes('not found in')) {
        resolve({ verdict: 'UNKNOWN_KEY', details: combined })
      } else {
        resolve({ verdict: 'ERRORED', details: combined || `ssh-keygen exited ${code}` })
      }
    })
  })
}

async function pickFingerprintForIdentity (allowedSignersPath: string, identity: string): Promise<string | null> {
  try {
    const text = await fs.readFile(allowedSignersPath, 'utf8')
    const match = text.split('\n').find((l) => {
      const principal = l.split(/\s+/)[0]
      return principal === identity || principal === '*'
    })
    if (!match) return null
    const parts = match.split(/\s+/)
    const keyMaterial = parts.slice(1).join(' ').trim()
    if (!keyMaterial) return null
    return await fingerprintSshKey(keyMaterial)
  } catch (e) {
    return null
  }
}

function fingerprintSshKey (keyLine: string): Promise<string | null> {
  return new Promise(async (resolve) => {
    const tmp = await fs.mkdtemp(path.join(os.tmpdir(), 'sshfp-'))
    const keyPath = path.join(tmp, 'k.pub')
    await fs.writeFile(keyPath, keyLine + '\n')
    const proc = spawn('ssh-keygen', ['-lf', keyPath])
    let out = ''
    proc.stdout.on('data', (chunk) => { out += chunk })
    proc.on('close', async () => {
      await fs.rm(tmp, { recursive: true, force: true }).catch(() => null)
      const parts = out.trim().split(/\s+/)
      // Output: <bits> SHA256:<fp> <comment> (<type>)
      resolve(parts[1] ?? null)
    })
  })
}

// gpg --verify <sig> <payload>, with the trust keyring assembled from
// the concatenated ASCII-armoured pubkeys.
async function verifyGpg (input: VerifyInput): Promise<VerifyResult> {
  const tmpDir = await fs.mkdtemp(path.join(os.tmpdir(), 'verify-gpg-'))
  try {
    const sigPath = path.join(tmpDir, 'signature.sig')
    const payloadPath = path.join(tmpDir, 'payload.bin')
    const keysPath = path.join(tmpDir, 'pubkeys.asc')
    await fs.writeFile(sigPath, Buffer.from(input.signatureB64, 'base64'))
    await fs.writeFile(payloadPath, Buffer.from(input.payloadB64, 'base64'))
    await fs.writeFile(keysPath, Buffer.from(input.trustStoreB64, 'base64'))

    if ((await fs.readFile(keysPath, 'utf8')).trim() === '') {
      return { verdict: 'UNKNOWN_KEY', details: 'Empty GPG keyring — no enrolled GPG keys for org' }
    }

    const gpgHome = path.join(tmpDir, 'gnupg')
    await fs.mkdir(gpgHome, { mode: 0o700 })
    const env = { ...process.env, GNUPGHOME: gpgHome }

    // Import enrolled keys.
    await runProc('gpg', ['--batch', '--import', keysPath], env)

    // Verify; gpg uses status-fd for machine-readable output.
    const verify = await runProc('gpg', ['--batch', '--status-fd', '1', '--verify', sigPath, payloadPath], env)
    const combined = `${verify.stdout}\n${verify.stderr}`.trim()
    const goodSigMatch = /\[GNUPG:\] (?:GOODSIG|VALIDSIG) ([0-9A-Fa-f]+)/.exec(combined)
    if (goodSigMatch) {
      return { verdict: 'VERIFIED', matchedFingerprint: goodSigMatch[1].toUpperCase(), details: combined }
    }
    if (/BADSIG|ERRSIG/.test(combined)) {
      return { verdict: 'INVALID_SIGNATURE', details: combined }
    }
    if (/NO_PUBKEY|NODATA|FAILURE/.test(combined)) {
      return { verdict: 'UNKNOWN_KEY', details: combined }
    }
    return { verdict: 'ERRORED', details: combined }
  } catch (e: any) {
    logger.error({ err: e }, 'GPG verifier failed')
    return { verdict: 'ERRORED', details: e?.message ?? String(e) }
  } finally {
    await fs.rm(tmpDir, { recursive: true, force: true }).catch(() => null)
  }
}

function runProc (cmd: string, args: string[], env: NodeJS.ProcessEnv): Promise<{ stdout: string, stderr: string, code: number | null }> {
  return new Promise((resolve) => {
    const proc = spawn(cmd, args, { env })
    let stdout = ''
    let stderr = ''
    proc.stdout.on('data', (chunk) => { stdout += chunk })
    proc.stderr.on('data', (chunk) => { stderr += chunk })
    proc.on('close', (code) => resolve({ stdout, stderr, code }))
  })
}
