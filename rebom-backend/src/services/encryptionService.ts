import crypto from 'crypto';
import { logger } from '../logger';

const ALGORITHM = 'aes-256-gcm';
const KEY_LENGTH = 32; // 256 bits
const IV_LENGTH = 12; // 96 bits (recommended for GCM)
const AUTH_TAG_LENGTH = 16; // 128 bits
const PBKDF2_ITERATIONS = 100_000;
const PBKDF2_DIGEST = 'sha256';

interface EncryptionKeyPair {
  key: Buffer;
  configured: boolean;
}

let newKeyPair: EncryptionKeyPair = { key: Buffer.alloc(0), configured: false };
let oldKeyPair: EncryptionKeyPair = { key: Buffer.alloc(0), configured: false };
let initialized = false;

/**
 * Derives an AES-256 key from a password and hex-encoded salt using PBKDF2.
 */
function deriveKey(password: string, saltHex: string): Buffer {
  const salt = Buffer.from(saltHex, 'hex');
  return crypto.pbkdf2Sync(password, salt, PBKDF2_ITERATIONS, KEY_LENGTH, PBKDF2_DIGEST);
}

/**
 * Initializes the encryption service from environment variables.
 * Must be called before encrypt/decrypt operations.
 * 
 * Required env vars:
 *   ENCRYPTION_PASSWORD - current encryption password
 *   ENCRYPTION_SALT     - current salt (hex-encoded, min 16 bytes / 32 hex chars)
 * 
 * Optional env vars (for key rotation):
 *   ENCRYPTION_OLD_PASSWORD - previous encryption password
 *   ENCRYPTION_OLD_SALT     - previous salt (hex-encoded)
 */
export function initEncryption(): void {
  let initPassword = process.env.ENCRYPTION_PASSWORD;
  let initSalt = process.env.ENCRYPTION_SALT;

  if (!initPassword || !initSalt) {
    logger.warn('Encryption service: ENCRYPTION_PASSWORD or ENCRYPTION_SALT not set — using default values. DO NOT USE IN PRODUCTION!!!');
    initPassword = 'bPeC2NQ+4cldt3ehhB/u5JGlqwKVqrQm9VqxuXc83RY=';
    initSalt = 'd33476046a4d76cfc97ca98d72d381da';
  }
  const password = initPassword;
  const salt = initSalt;

  if (salt.length < 32) {
    throw new Error('ENCRYPTION_SALT must be at least 16 bytes (32 hex characters)');
  }

  newKeyPair = { key: deriveKey(password, salt), configured: true };

  const oldPassword = process.env.ENCRYPTION_OLD_PASSWORD;
  const oldSalt = process.env.ENCRYPTION_OLD_SALT;

  if (oldPassword && oldSalt) {
    oldKeyPair = { key: deriveKey(oldPassword, oldSalt), configured: true };
    logger.info('Encryption service: Initialized with new and old keys (rotation enabled)');
  } else {
    oldKeyPair = { key: Buffer.alloc(0), configured: false };
    logger.info('Encryption service: Initialized with new key only');
  }

  initialized = true;
}

/**
 * Returns whether the encryption service is initialized and ready.
 */
export function isEncryptionConfigured(): boolean {
  return initialized && newKeyPair.configured;
}

/**
 * Encrypts plaintext using AES-256-GCM with the current key.
 * 
 * Output format (hex-encoded): IV (12 bytes) + ciphertext + authTag (16 bytes)
 * 
 * @param plainText - string to encrypt
 * @returns hex-encoded ciphertext
 * @throws if encryption service is not initialized
 */
export function encrypt(plainText: string): string {
  if (!isEncryptionConfigured()) {
    throw new Error('Encryption service not initialized — call initEncryption() first');
  }

  const iv = crypto.randomBytes(IV_LENGTH);
  const cipher = crypto.createCipheriv(ALGORITHM, newKeyPair.key, iv, { authTagLength: AUTH_TAG_LENGTH });

  const encrypted = Buffer.concat([
    cipher.update(plainText, 'utf8'),
    cipher.final()
  ]);

  const authTag = cipher.getAuthTag();

  // Format: IV + ciphertext + authTag
  return Buffer.concat([iv, encrypted, authTag]).toString('hex');
}

/**
 * Decrypts hex-encoded ciphertext produced by encrypt().
 * Tries the current key first, then falls back to the old key for rotation support.
 * 
 * @param cypherText - hex-encoded ciphertext (IV + ciphertext + authTag)
 * @returns decrypted plaintext, or null if decryption fails with both keys
 */
export function decrypt(cypherText: string): string | null {
  if (!isEncryptionConfigured()) {
    throw new Error('Encryption service not initialized — call initEncryption() first');
  }

  // Try new key first
  const result = decryptWithKey(cypherText, newKeyPair.key);
  if (result !== null) {
    return result;
  }

  // Fall back to old key
  if (oldKeyPair.configured) {
    const oldResult = decryptWithKey(cypherText, oldKeyPair.key);
    if (oldResult !== null) {
      return oldResult;
    }
  }

  logger.warn('Encryption service: Decryption failed with both new and old keys');
  return null;
}

/**
 * Attempts to decrypt ciphertext with a specific key.
 * 
 * @returns decrypted plaintext, or null on failure
 */
function decryptWithKey(cypherText: string, key: Buffer): string | null {
  try {
    const data = Buffer.from(cypherText, 'hex');

    if (data.length < IV_LENGTH + AUTH_TAG_LENGTH) {
      return null;
    }

    const iv = data.subarray(0, IV_LENGTH);
    const authTag = data.subarray(data.length - AUTH_TAG_LENGTH);
    const encrypted = data.subarray(IV_LENGTH, data.length - AUTH_TAG_LENGTH);

    const decipher = crypto.createDecipheriv(ALGORITHM, key, iv, { authTagLength: AUTH_TAG_LENGTH });
    decipher.setAuthTag(authTag);

    const decrypted = Buffer.concat([
      decipher.update(encrypted),
      decipher.final()
    ]);

    return decrypted.toString('utf8');
  } catch {
    return null;
  }
}
