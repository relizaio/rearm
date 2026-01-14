/**
 * Error Handling Tests for Database Query Layer
 * 
 * These tests verify that database errors are properly caught and handled
 * without crashing the Node.js process (similar to Spring Boot behavior).
 */

import { describe, it, expect, beforeEach, vi } from 'vitest';
import { runQuery } from '../query';
import { pool } from '../pool';
import { logger } from '../../logger';

// Mock the logger to prevent console output during tests
vi.mock('../../logger', () => ({
    logger: {
        error: vi.fn(),
        info: vi.fn(),
        warn: vi.fn(),
        debug: vi.fn()
    }
}));

describe('Database Query Error Handling', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    describe('Connection Pool Errors', () => {
        it('should handle connection pool exhaustion gracefully', async () => {
            // Mock pool.connect to simulate connection failure
            const mockConnect = vi.spyOn(pool, 'connect').mockRejectedValueOnce(
                new Error('Connection pool exhausted')
            );

            await expect(runQuery('SELECT 1', [])).rejects.toThrow('Database connection failed');
            expect(logger.error).toHaveBeenCalledWith(
                expect.objectContaining({
                    message: 'Failed to acquire database connection from pool'
                }),
                'Database connection error'
            );

            mockConnect.mockRestore();
        });

        it('should handle network connection errors gracefully', async () => {
            const mockConnect = vi.spyOn(pool, 'connect').mockRejectedValueOnce(
                Object.assign(new Error('ECONNREFUSED'), { code: 'ECONNREFUSED' })
            );

            await expect(runQuery('SELECT 1', [])).rejects.toThrow('Database connection failed');
            expect(logger.error).toHaveBeenCalled();

            mockConnect.mockRestore();
        });
    });

    describe('PostgreSQL Constraint Violations', () => {
        it('should handle unique constraint violations (23505)', async () => {
            const mockClient = {
                query: vi.fn().mockRejectedValueOnce(
                    Object.assign(new Error('duplicate key value'), {
                        code: '23505',
                        detail: 'Key (uuid)=(abc-123) already exists',
                        constraint: 'boms_uuid_key'
                    })
                ),
                release: vi.fn()
            };

            vi.spyOn(pool, 'connect').mockResolvedValueOnce(mockClient as any);

            await expect(runQuery('INSERT INTO ...', [])).rejects.toThrow('Duplicate entry');
            expect(logger.error).toHaveBeenCalledWith(
                expect.objectContaining({
                    errorCode: '23505'
                }),
                'Database query execution error'
            );
            expect(mockClient.release).toHaveBeenCalled();
        });

        it('should handle not-null constraint violations (23502)', async () => {
            const mockClient = {
                query: vi.fn().mockRejectedValueOnce(
                    Object.assign(new Error('null value in column'), {
                        code: '23502',
                        detail: 'Failing row contains (null, ...)',
                        column: 'organization'
                    })
                ),
                release: vi.fn()
            };

            vi.spyOn(pool, 'connect').mockResolvedValueOnce(mockClient as any);

            await expect(runQuery('INSERT INTO ...', [])).rejects.toThrow('Required field cannot be null');
            expect(mockClient.release).toHaveBeenCalled();
        });

        it('should handle foreign key constraint violations (23503)', async () => {
            const mockClient = {
                query: vi.fn().mockRejectedValueOnce(
                    Object.assign(new Error('foreign key violation'), {
                        code: '23503',
                        detail: 'Key (parent_id)=(xyz) is not present in table "parents"'
                    })
                ),
                release: vi.fn()
            };

            vi.spyOn(pool, 'connect').mockResolvedValueOnce(mockClient as any);

            await expect(runQuery('INSERT INTO ...', [])).rejects.toThrow('Invalid reference');
            expect(mockClient.release).toHaveBeenCalled();
        });

        it('should handle check constraint violations (23514)', async () => {
            const mockClient = {
                query: vi.fn().mockRejectedValueOnce(
                    Object.assign(new Error('check constraint violation'), {
                        code: '23514',
                        detail: 'Failing row violates check constraint "valid_version"'
                    })
                ),
                release: vi.fn()
            };

            vi.spyOn(pool, 'connect').mockResolvedValueOnce(mockClient as any);

            await expect(runQuery('INSERT INTO ...', [])).rejects.toThrow('Data validation failed');
            expect(mockClient.release).toHaveBeenCalled();
        });
    });

    describe('PostgreSQL Schema Errors', () => {
        it('should handle undefined table errors (42P01)', async () => {
            const mockClient = {
                query: vi.fn().mockRejectedValueOnce(
                    Object.assign(new Error('relation "nonexistent_table" does not exist'), {
                        code: '42P01'
                    })
                ),
                release: vi.fn()
            };

            vi.spyOn(pool, 'connect').mockResolvedValueOnce(mockClient as any);

            await expect(runQuery('SELECT * FROM nonexistent_table', [])).rejects.toThrow('Database table not found');
            expect(mockClient.release).toHaveBeenCalled();
        });

        it('should handle undefined column errors (42703)', async () => {
            const mockClient = {
                query: vi.fn().mockRejectedValueOnce(
                    Object.assign(new Error('column "nonexistent_column" does not exist'), {
                        code: '42703'
                    })
                ),
                release: vi.fn()
            };

            vi.spyOn(pool, 'connect').mockResolvedValueOnce(mockClient as any);

            await expect(runQuery('SELECT nonexistent_column FROM ...', [])).rejects.toThrow('Database column not found');
            expect(mockClient.release).toHaveBeenCalled();
        });
    });

    describe('PostgreSQL Data Type Errors', () => {
        it('should handle invalid text representation errors (22P02)', async () => {
            const mockClient = {
                query: vi.fn().mockRejectedValueOnce(
                    Object.assign(new Error('invalid input syntax for type uuid'), {
                        code: '22P02'
                    })
                ),
                release: vi.fn()
            };

            vi.spyOn(pool, 'connect').mockResolvedValueOnce(mockClient as any);

            await expect(runQuery('SELECT * WHERE uuid = $1', ['not-a-uuid'])).rejects.toThrow('Invalid data format');
            expect(mockClient.release).toHaveBeenCalled();
        });
    });

    describe('PostgreSQL Connection Errors', () => {
        it('should handle connection lost errors (08006)', async () => {
            const mockClient = {
                query: vi.fn().mockRejectedValueOnce(
                    Object.assign(new Error('connection lost'), {
                        code: '08006'
                    })
                ),
                release: vi.fn()
            };

            vi.spyOn(pool, 'connect').mockResolvedValueOnce(mockClient as any);

            await expect(runQuery('SELECT 1', [])).rejects.toThrow('Database connection lost');
            expect(mockClient.release).toHaveBeenCalled();
        });

        it('should handle too many connections errors (53300)', async () => {
            const mockClient = {
                query: vi.fn().mockRejectedValueOnce(
                    Object.assign(new Error('too many connections'), {
                        code: '53300'
                    })
                ),
                release: vi.fn()
            };

            vi.spyOn(pool, 'connect').mockResolvedValueOnce(mockClient as any);

            await expect(runQuery('SELECT 1', [])).rejects.toThrow('Database connection limit reached');
            expect(mockClient.release).toHaveBeenCalled();
        });
    });

    describe('Query Cancellation', () => {
        it('should handle query canceled errors (57014)', async () => {
            const mockClient = {
                query: vi.fn().mockRejectedValueOnce(
                    Object.assign(new Error('canceling statement due to user request'), {
                        code: '57014'
                    })
                ),
                release: vi.fn()
            };

            vi.spyOn(pool, 'connect').mockResolvedValueOnce(mockClient as any);

            await expect(runQuery('SELECT * FROM large_table', [])).rejects.toThrow('Query was canceled');
            expect(mockClient.release).toHaveBeenCalled();
        });
    });

    describe('Unknown Errors', () => {
        it('should handle unknown database errors gracefully', async () => {
            const mockClient = {
                query: vi.fn().mockRejectedValueOnce(
                    Object.assign(new Error('Unknown database error'), {
                        code: '99999' // Unknown error code
                    })
                ),
                release: vi.fn()
            };

            vi.spyOn(pool, 'connect').mockResolvedValueOnce(mockClient as any);

            await expect(runQuery('SELECT 1', [])).rejects.toThrow('Database operation failed');
            expect(logger.error).toHaveBeenCalledWith(
                expect.objectContaining({
                    errorCode: '99999'
                }),
                'Database query execution error'
            );
            expect(mockClient.release).toHaveBeenCalled();
        });

        it('should handle errors without error codes', async () => {
            const mockClient = {
                query: vi.fn().mockRejectedValueOnce(
                    new Error('Generic error without code')
                ),
                release: vi.fn()
            };

            vi.spyOn(pool, 'connect').mockResolvedValueOnce(mockClient as any);

            await expect(runQuery('SELECT 1', [])).rejects.toThrow('Database operation failed');
            expect(mockClient.release).toHaveBeenCalled();
        });
    });

    describe('Client Release Errors', () => {
        it('should handle client release errors without throwing', async () => {
            const mockClient = {
                query: vi.fn().mockResolvedValueOnce({ rows: [] }),
                release: vi.fn().mockRejectedValueOnce(new Error('Release failed'))
            };

            vi.spyOn(pool, 'connect').mockResolvedValueOnce(mockClient as any);

            // Should not throw even if release fails
            await expect(runQuery('SELECT 1', [])).resolves.toBeDefined();
            expect(logger.error).toHaveBeenCalledWith(
                expect.objectContaining({
                    err: expect.any(Error)
                }),
                'Error releasing database client to pool'
            );
        });
    });

    describe('Successful Queries', () => {
        it('should execute successful queries without errors', async () => {
            const mockResult = { rows: [{ id: 1, name: 'test' }], rowCount: 1 };
            const mockClient = {
                query: vi.fn().mockResolvedValueOnce(mockResult),
                release: vi.fn()
            };

            vi.spyOn(pool, 'connect').mockResolvedValueOnce(mockClient as any);

            const result = await runQuery('SELECT * FROM test', []);
            
            expect(result).toEqual(mockResult);
            expect(mockClient.query).toHaveBeenCalledWith('SELECT * FROM test', []);
            expect(mockClient.release).toHaveBeenCalled();
            expect(logger.error).not.toHaveBeenCalled();
        });
    });

    describe('Error Logging', () => {
        it('should log query details (truncated) on error', async () => {
            const longQuery = 'SELECT * FROM table WHERE ' + 'x = 1 AND '.repeat(50) + 'y = 2';
            const mockClient = {
                query: vi.fn().mockRejectedValueOnce(
                    Object.assign(new Error('Query failed'), { code: '42P01' })
                ),
                release: vi.fn()
            };

            vi.spyOn(pool, 'connect').mockResolvedValueOnce(mockClient as any);

            await expect(runQuery(longQuery, [])).rejects.toThrow();
            
            // Verify query is truncated to 200 chars in logs
            expect(logger.error).toHaveBeenCalledWith(
                expect.objectContaining({
                    query: expect.stringMatching(/^.{1,200}$/)
                }),
                'Database query execution error'
            );
        });

        it('should log error details including code, detail, hint, and constraint', async () => {
            const mockClient = {
                query: vi.fn().mockRejectedValueOnce(
                    Object.assign(new Error('Constraint violation'), {
                        code: '23505',
                        detail: 'Key already exists',
                        hint: 'Use UPDATE instead',
                        constraint: 'unique_key'
                    })
                ),
                release: vi.fn()
            };

            vi.spyOn(pool, 'connect').mockResolvedValueOnce(mockClient as any);

            await expect(runQuery('INSERT ...', [])).rejects.toThrow();
            
            expect(logger.error).toHaveBeenCalledWith(
                expect.objectContaining({
                    errorCode: '23505',
                    errorDetail: 'Key already exists',
                    errorHint: 'Use UPDATE instead',
                    constraint: 'unique_key'
                }),
                'Database query execution error'
            );
        });
    });
});
