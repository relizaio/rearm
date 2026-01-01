export interface BomDiffResult {
    componentVersions: {
        [key: string]: {
            added: Component[];
            removed: Component[];
            unchanged: Component[];
        };
    };
}

export interface ComponentDiff {
    added: Array<{ purl: string; version: string }>;
    removed: Array<{ purl: string; version: string }>;
}

export interface Component {
    type: 'library';
    'bom-ref': string;
    group: string;
    name: string;
    version: string;
    hashes: Hash[];
    licenses: License[];
    purl: string;
}

export interface Hash {
    alg: string;
    content: string;
}

export interface License {
    license: {
        name: string;
    };
}