import { encrypt, decrypt, initEncryption, isEncryptionConfigured } from '../../src/services/encryptionService';

describe('EncryptionService', () => {
    const TEST_PASSWORD = 'test-password-for-encryption';
    const TEST_SALT = 'a1b2c3d4e5f6a7b8a1b2c3d4e5f6a7b8'; // 32 hex chars = 16 bytes
    const OLD_PASSWORD = 'old-password-for-rotation';
    const OLD_SALT = 'f8e7d6c5b4a3f2e1f8e7d6c5b4a3f2e1';

    beforeEach(() => {
        // Clean env vars before each test
        delete process.env.ENCRYPTION_PASSWORD;
        delete process.env.ENCRYPTION_SALT;
        delete process.env.ENCRYPTION_OLD_PASSWORD;
        delete process.env.ENCRYPTION_OLD_SALT;
    });

    describe('initEncryption', () => {
        it('should initialize with new key only', () => {
            process.env.ENCRYPTION_PASSWORD = TEST_PASSWORD;
            process.env.ENCRYPTION_SALT = TEST_SALT;

            initEncryption();

            expect(isEncryptionConfigured()).toBe(true);
        });

        it('should initialize with new and old keys', () => {
            process.env.ENCRYPTION_PASSWORD = TEST_PASSWORD;
            process.env.ENCRYPTION_SALT = TEST_SALT;
            process.env.ENCRYPTION_OLD_PASSWORD = OLD_PASSWORD;
            process.env.ENCRYPTION_OLD_SALT = OLD_SALT;

            initEncryption();

            expect(isEncryptionConfigured()).toBe(true);
        });

        it('should initialize with default password when not provided', () => {
            process.env.ENCRYPTION_SALT = TEST_SALT;

            initEncryption();

            // Service uses default password and initializes successfully
            expect(isEncryptionConfigured()).toBe(true);
        });

        it('should initialize with default salt when not provided', () => {
            process.env.ENCRYPTION_PASSWORD = TEST_PASSWORD;

            initEncryption();

            // Service uses default salt and initializes successfully
            expect(isEncryptionConfigured()).toBe(true);
        });

        it('should throw if salt is too short', () => {
            process.env.ENCRYPTION_PASSWORD = TEST_PASSWORD;
            process.env.ENCRYPTION_SALT = 'abcd'; // too short

            expect(() => initEncryption()).toThrow('ENCRYPTION_SALT must be at least 16 bytes');
        });
    });

    describe('encrypt and decrypt', () => {
        beforeEach(() => {
            process.env.ENCRYPTION_PASSWORD = TEST_PASSWORD;
            process.env.ENCRYPTION_SALT = TEST_SALT;
            initEncryption();
        });

        it('should encrypt and decrypt a simple string', () => {
            const plainText = 'hello world';
            const encrypted = encrypt(plainText);
            const decrypted = decrypt(encrypted);

            expect(decrypted).toBe(plainText);
        });

        it('should encrypt and decrypt an empty string', () => {
            const plainText = '';
            const encrypted = encrypt(plainText);
            const decrypted = decrypt(encrypted);

            expect(decrypted).toBe(plainText);
        });

        it('should encrypt and decrypt unicode text', () => {
            const plainText = '日本語テスト 🔐';
            const encrypted = encrypt(plainText);
            const decrypted = decrypt(encrypted);

            expect(decrypted).toBe(plainText);
        });

        it('should encrypt and decrypt a long string', () => {
            const plainText = 'a'.repeat(10000);
            const encrypted = encrypt(plainText);
            const decrypted = decrypt(encrypted);

            expect(decrypted).toBe(plainText);
        });

        it('should produce different ciphertext for the same plaintext (random IV)', () => {
            const plainText = 'deterministic test';
            const encrypted1 = encrypt(plainText);
            const encrypted2 = encrypt(plainText);

            expect(encrypted1).not.toBe(encrypted2);

            // Both should decrypt to the same value
            expect(decrypt(encrypted1)).toBe(plainText);
            expect(decrypt(encrypted2)).toBe(plainText);
        });

        it('should return hex-encoded output', () => {
            const encrypted = encrypt('test');
            expect(encrypted).toMatch(/^[0-9a-f]+$/);
        });

        it('should return null for tampered ciphertext', () => {
            const encrypted = encrypt('test');
            // Flip a character in the middle of the ciphertext
            const mid = Math.floor(encrypted.length / 2);
            const tampered = encrypted.substring(0, mid) +
                (encrypted[mid] === 'a' ? 'b' : 'a') +
                encrypted.substring(mid + 1);

            expect(decrypt(tampered)).toBeNull();
        });

        it('should return null for garbage input', () => {
            expect(decrypt('not-valid-hex')).toBeNull();
        });

        it('should return null for too-short input', () => {
            expect(decrypt('abcd')).toBeNull();
        });
    });

    describe('key rotation', () => {
        it('should decrypt data encrypted with old key after rotation', () => {
            // Step 1: Encrypt with "old" key (which is current at the time)
            process.env.ENCRYPTION_PASSWORD = OLD_PASSWORD;
            process.env.ENCRYPTION_SALT = OLD_SALT;
            initEncryption();

            const plainText = 'secret data before rotation';
            const encryptedWithOldKey = encrypt(plainText);

            // Step 2: Rotate to new key, keeping old key for fallback
            process.env.ENCRYPTION_PASSWORD = TEST_PASSWORD;
            process.env.ENCRYPTION_SALT = TEST_SALT;
            process.env.ENCRYPTION_OLD_PASSWORD = OLD_PASSWORD;
            process.env.ENCRYPTION_OLD_SALT = OLD_SALT;
            initEncryption();

            // Should decrypt old ciphertext via fallback
            expect(decrypt(encryptedWithOldKey)).toBe(plainText);
        });

        it('should encrypt with new key after rotation', () => {
            // Set up rotated keys
            process.env.ENCRYPTION_PASSWORD = TEST_PASSWORD;
            process.env.ENCRYPTION_SALT = TEST_SALT;
            process.env.ENCRYPTION_OLD_PASSWORD = OLD_PASSWORD;
            process.env.ENCRYPTION_OLD_SALT = OLD_SALT;
            initEncryption();

            const plainText = 'new data after rotation';
            const encrypted = encrypt(plainText);

            // Should decrypt with current key
            expect(decrypt(encrypted)).toBe(plainText);

            // Verify it was encrypted with NEW key (not old)
            // Re-init with only new key — should still decrypt
            delete process.env.ENCRYPTION_OLD_PASSWORD;
            delete process.env.ENCRYPTION_OLD_SALT;
            initEncryption();

            expect(decrypt(encrypted)).toBe(plainText);
        });

        it('should return null if neither key can decrypt', () => {
            process.env.ENCRYPTION_PASSWORD = TEST_PASSWORD;
            process.env.ENCRYPTION_SALT = TEST_SALT;
            process.env.ENCRYPTION_OLD_PASSWORD = OLD_PASSWORD;
            process.env.ENCRYPTION_OLD_SALT = OLD_SALT;
            initEncryption();

            // Encrypt with a completely different key
            process.env.ENCRYPTION_PASSWORD = 'totally-different-password';
            process.env.ENCRYPTION_SALT = 'deadbeefdeadbeefdeadbeefdeadbeef';
            delete process.env.ENCRYPTION_OLD_PASSWORD;
            delete process.env.ENCRYPTION_OLD_SALT;
            initEncryption();

            const encrypted = encrypt('some data');

            // Re-init with original keys — neither should work
            process.env.ENCRYPTION_PASSWORD = TEST_PASSWORD;
            process.env.ENCRYPTION_SALT = TEST_SALT;
            process.env.ENCRYPTION_OLD_PASSWORD = OLD_PASSWORD;
            process.env.ENCRYPTION_OLD_SALT = OLD_SALT;
            initEncryption();

            expect(decrypt(encrypted)).toBeNull();
        });
    });

    describe('default values', () => {
        it('should initialize with defaults when no env vars are set', () => {
            // Clean env
            delete process.env.ENCRYPTION_PASSWORD;
            delete process.env.ENCRYPTION_SALT;
            delete process.env.ENCRYPTION_OLD_PASSWORD;
            delete process.env.ENCRYPTION_OLD_SALT;

            initEncryption();

            // Service should initialize with default values
            expect(isEncryptionConfigured()).toBe(true);
        });

        it('should encrypt and decrypt with default values', () => {
            // Clean env
            delete process.env.ENCRYPTION_PASSWORD;
            delete process.env.ENCRYPTION_SALT;
            delete process.env.ENCRYPTION_OLD_PASSWORD;
            delete process.env.ENCRYPTION_OLD_SALT;

            initEncryption();

            const plainText = 'test with defaults';
            const encrypted = encrypt(plainText);
            const decrypted = decrypt(encrypted);

            expect(decrypted).toBe(plainText);
        });
    });
});
