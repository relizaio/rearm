import { runQuery } from './db';
import { IntegrationRecord, SecretRecord, IntegrationType } from './types';
import { logger } from './logger';

export async function findIntegrationByTypeAndOrg(type: IntegrationType, org: string): Promise<IntegrationRecord | null> {
    try {
        const queryRes = await runQuery(
            `SELECT * FROM rebom.integrations WHERE config->>'type' = $1 AND organization::text = $2`,
            [type, org]
        );
        return queryRes.rows.length > 0 ? queryRes.rows[0] as IntegrationRecord : null;
    } catch (error) {
        logger.error({ error, type, org }, 'Error finding integration by type and org');
        throw error;
    }
}

export async function upsertIntegration(config: any, org: string): Promise<IntegrationRecord> {
    try {
        const type = config.type;
        const queryRes = await runQuery(
            `INSERT INTO rebom.integrations (config, organization)
             VALUES ($1::jsonb, $2::uuid)
             ON CONFLICT ( (config->>'type'), organization )
             DO UPDATE SET config = $1::jsonb, last_updated_date = now()
             RETURNING *`,
            [JSON.stringify(config), org]
        );
        return queryRes.rows[0] as IntegrationRecord;
    } catch (error) {
        logger.error({ error, type: config.type, org }, 'Error upserting integration');
        throw error;
    }
}

export async function createSecret(encryptedValue: string, org: string): Promise<SecretRecord> {
    try {
        const queryRes = await runQuery(
            `INSERT INTO rebom.secrets (encrypted_value, organization)
             VALUES ($1, $2::uuid)
             RETURNING *`,
            [encryptedValue, org]
        );
        return queryRes.rows[0] as SecretRecord;
    } catch (error) {
        logger.error({ error, org }, 'Error creating secret');
        throw error;
    }
}

export async function findSecretById(uuid: string): Promise<SecretRecord | null> {
    try {
        const queryRes = await runQuery(
            `SELECT * FROM rebom.secrets WHERE uuid = $1`,
            [uuid]
        );
        return queryRes.rows.length > 0 ? queryRes.rows[0] as SecretRecord : null;
    } catch (error) {
        logger.error({ error, uuid }, 'Error finding secret by ID');
        throw error;
    }
}

export async function updateSecret(uuid: string, encryptedValue: string): Promise<SecretRecord> {
    try {
        const queryRes = await runQuery(
            `UPDATE rebom.secrets SET encrypted_value = $1, last_updated_date = now() WHERE uuid = $2 RETURNING *`,
            [encryptedValue, uuid]
        );
        return queryRes.rows[0] as SecretRecord;
    } catch (error) {
        logger.error({ error, uuid }, 'Error updating secret');
        throw error;
    }
}

export async function deleteIntegration(uuid: string): Promise<void> {
    try {
        await runQuery(`DELETE FROM rebom.integrations WHERE uuid = $1`, [uuid]);
    } catch (error) {
        logger.error({ error, uuid }, 'Error deleting integration');
        throw error;
    }
}

export async function deleteSecret(uuid: string): Promise<void> {
    try {
        await runQuery(`DELETE FROM rebom.secrets WHERE uuid = $1`, [uuid]);
    } catch (error) {
        logger.error({ error, uuid }, 'Error deleting secret');
        throw error;
    }
}
