/**
 * Structured error types for rebom-backend
 * These errors are designed to cross the GraphQL boundary with structured information
 */

export class BomValidationError extends Error {
    constructor(
        message: string,
        public details?: {
            field?: string;
            value?: any;
            constraint?: string;
            [key: string]: any;
        }
    ) {
        super(message);
        this.name = 'BomValidationError';
        Error.captureStackTrace(this, this.constructor);
    }
}

export class BomStorageError extends Error {
    constructor(
        message: string,
        public cause?: Error,
        public context?: {
            bomId?: string;
            operation?: string;
            [key: string]: any;
        }
    ) {
        super(message);
        this.name = 'BomStorageError';
        Error.captureStackTrace(this, this.constructor);
    }
}

export class BomNotFoundError extends Error {
    constructor(
        message: string,
        public bomId?: string,
        public searchCriteria?: Record<string, any>
    ) {
        super(message);
        this.name = 'BomNotFoundError';
        Error.captureStackTrace(this, this.constructor);
    }
}

export class BomConversionError extends Error {
    constructor(
        message: string,
        public sourceFormat?: string,
        public targetFormat?: string,
        public cause?: Error
    ) {
        super(message);
        this.name = 'BomConversionError';
        Error.captureStackTrace(this, this.constructor);
    }
}

export class BomMergeError extends Error {
    constructor(
        message: string,
        public bomIds?: string[],
        public cause?: Error
    ) {
        super(message);
        this.name = 'BomMergeError';
        Error.captureStackTrace(this, this.constructor);
    }
}

export class OciStorageError extends Error {
    constructor(
        message: string,
        public operation?: 'push' | 'fetch',
        public uuid?: string,
        public cause?: Error
    ) {
        super(message);
        this.name = 'OciStorageError';
        Error.captureStackTrace(this, this.constructor);
    }
}

export class BomDataIntegrityError extends Error {
    constructor(
        message: string,
        public identifier: string,
        public count: number,
        public context?: {
            org?: string;
            version?: number;
            searchType?: string;
            [key: string]: any;
        }
    ) {
        super(message);
        this.name = 'BomDataIntegrityError';
        Error.captureStackTrace(this, this.constructor);
    }
}

/**
 * Error code mapping for GraphQL responses
 */
export const ERROR_CODES = {
    BOM_VALIDATION_ERROR: 'BOM_VALIDATION_ERROR',
    BOM_STORAGE_ERROR: 'BOM_STORAGE_ERROR',
    BOM_NOT_FOUND: 'BOM_NOT_FOUND',
    BOM_CONVERSION_ERROR: 'BOM_CONVERSION_ERROR',
    BOM_MERGE_ERROR: 'BOM_MERGE_ERROR',
    OCI_STORAGE_ERROR: 'OCI_STORAGE_ERROR',
    BOM_DATA_INTEGRITY_ERROR: 'BOM_DATA_INTEGRITY_ERROR',
    INTERNAL_ERROR: 'INTERNAL_ERROR',
} as const;

export type ErrorCode = typeof ERROR_CODES[keyof typeof ERROR_CODES];

/**
 * Convert error to GraphQL error format
 */
export function toGraphQLError(error: Error): {
    message: string;
    extensions: {
        code: ErrorCode;
        details?: any;
    };
} {
    if (error instanceof BomValidationError) {
        return {
            message: error.message,
            extensions: {
                code: ERROR_CODES.BOM_VALIDATION_ERROR,
                details: error.details,
            },
        };
    }

    if (error instanceof BomStorageError) {
        return {
            message: error.message,
            extensions: {
                code: ERROR_CODES.BOM_STORAGE_ERROR,
                details: {
                    context: error.context,
                    cause: error.cause?.message,
                },
            },
        };
    }

    if (error instanceof BomNotFoundError) {
        return {
            message: error.message,
            extensions: {
                code: ERROR_CODES.BOM_NOT_FOUND,
                details: {
                    bomId: error.bomId,
                    searchCriteria: error.searchCriteria,
                },
            },
        };
    }

    if (error instanceof BomConversionError) {
        return {
            message: error.message,
            extensions: {
                code: ERROR_CODES.BOM_CONVERSION_ERROR,
                details: {
                    sourceFormat: error.sourceFormat,
                    targetFormat: error.targetFormat,
                    cause: error.cause?.message,
                },
            },
        };
    }

    if (error instanceof BomMergeError) {
        return {
            message: error.message,
            extensions: {
                code: ERROR_CODES.BOM_MERGE_ERROR,
                details: {
                    bomIds: error.bomIds,
                    cause: error.cause?.message,
                },
            },
        };
    }

    if (error instanceof OciStorageError) {
        return {
            message: error.message,
            extensions: {
                code: ERROR_CODES.OCI_STORAGE_ERROR,
                details: {
                    operation: error.operation,
                    uuid: error.uuid,
                    cause: error.cause?.message,
                },
            },
        };
    }

    if (error instanceof BomDataIntegrityError) {
        return {
            message: error.message,
            extensions: {
                code: ERROR_CODES.BOM_DATA_INTEGRITY_ERROR,
                details: {
                    identifier: error.identifier,
                    count: error.count,
                    context: error.context,
                },
            },
        };
    }

    // Generic error
    return {
        message: error.message || 'An unexpected error occurred',
        extensions: {
            code: ERROR_CODES.INTERNAL_ERROR,
            details: {
                name: error.name,
            },
        },
    };
}
