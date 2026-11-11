import { TeaEffectiveLimits, TeaLocalTesting } from './teaConfig';
import { TEA_ARTIFACT_TYPE_BOM, TEA_ARTIFACT_TYPE_VULNERABILITIES, TeaDocumentClass } from './teaDocument';
import { isPlainHttp, TeaParentKind, TeaReleaseKind, TeaRequest, TeaVerb } from './teaRequest';

/**
 * The argument vector of a run (TPF-180 design 3.5, 3.6.5, 3.8). Nothing else builds arguments.
 * Every flag with a value is one element of the form --flag=value, so a value cannot become a flag
 * or a command. Every limit flag is always passed, with a positive value. Never passed: -d,
 * --credential-stdin. Passed only under local testing: --allow-this-host, --usehttp, --useport.
 */

/** The CLI's own defaults, kept as constants: nothing in a deployment needs them different. */
export const TEA_MAX_REDIRECTS = 5;
export const TEA_MAX_STRING_BYTES = 8192;

function flag(name: string, value: string | number): string {
    return `--${name}=${value}`;
}

/**
 * The selection flags of a set of document classes: types nobody knows yet stay under OTHER,
 * so OTHER is expressed by exclusion. The same mapping as documentClassOf.
 */
export function selectionFlags(classes: readonly TeaDocumentClass[]): string[] {
    const sbom = classes.includes(TeaDocumentClass.SBOM);
    const vex = classes.includes(TeaDocumentClass.VEX);
    const other = classes.includes(TeaDocumentClass.OTHER);
    if (other) {
        const out: string[] = [];
        if (!sbom) out.push(flag('exclude-artifact-type', TEA_ARTIFACT_TYPE_BOM));
        if (!vex) out.push(flag('exclude-artifact-type', TEA_ARTIFACT_TYPE_VULNERABILITIES));
        return out;
    }
    const out: string[] = [];
    if (sbom) out.push(flag('artifact-type', TEA_ARTIFACT_TYPE_BOM));
    if (vex) out.push(flag('artifact-type', TEA_ARTIFACT_TYPE_VULNERABILITIES));
    return out;
}

function releaseFlag(kind: TeaReleaseKind | undefined, id: string | undefined): string {
    return flag(kind === TeaReleaseKind.COMPONENT_RELEASE ? 'component-release' : 'product-release', id!);
}

/**
 * The whole vector for a validated request. downloadDir is the not yet existing --dir of a
 * download (inside the per-call directory); ignored for the other verbs.
 */
export function buildTeaArgv(request: TeaRequest, limits: TeaEffectiveLimits, localTesting: TeaLocalTesting, downloadDir?: string): string[] {
    const argv = ['tea', 'machine', request.verb];
    switch (request.verb) {
        case TeaVerb.RESOLVE:
            argv.push(flag('tei', request.tei!));
            break;
        case TeaVerb.PRODUCT:
        case TeaVerb.COMPONENT:
            argv.push(flag('api-root', request.apiRoot!), flag('uuid', request.uuid!));
            break;
        case TeaVerb.RELEASES:
            argv.push(flag('api-root', request.apiRoot!),
                flag(request.parentKind === TeaParentKind.COMPONENT ? 'component' : 'product', request.parentUuid!));
            if (request.pageToken) argv.push(flag('page-token', request.pageToken));
            break;
        case TeaVerb.RELEASE:
            argv.push(flag('api-root', request.apiRoot!), releaseFlag(request.releaseKind, request.releaseUuid));
            break;
        case TeaVerb.DOWNLOAD:
            argv.push(flag('api-root', request.apiRoot!), releaseFlag(request.releaseKind, request.releaseUuid),
                flag('collection-version', request.collectionVersion!), flag('dir', downloadDir!));
            break;
    }
    argv.push(
        flag('connect-timeout', `${limits.connectTimeoutSeconds}s`),
        flag('deadline', `${limits.deadlineSeconds}s`),
        flag('max-document-bytes', limits.maxDocumentBytes),
        flag('max-redirects', TEA_MAX_REDIRECTS),
        flag('max-string-bytes', TEA_MAX_STRING_BYTES),
        flag('max-output-bytes', limits.maxOutputBytes)
    );
    if (request.verb === TeaVerb.RELEASES) {
        argv.push(flag('page-size', limits.pageSize!), flag('max-pages', limits.maxPages!));
    }
    if (request.verb === TeaVerb.DOWNLOAD) {
        argv.push(
            flag('max-artifact-bytes', limits.maxArtifactBytes!),
            flag('max-total-bytes', limits.maxTotalBytes!),
            flag('max-files', limits.maxFiles!),
            ...selectionFlags(request.documentClasses ?? []),
            ...(request.mediaTypes ?? []).map((mediaType) => flag('media-type', mediaType))
        );
    }
    if (localTesting === TeaLocalTesting.ON) {
        argv.push('--allow-this-host');
        if (request.verb === TeaVerb.RESOLVE) {
            if (request.localPlainHttpPort) argv.push('--usehttp', flag('useport', request.localPlainHttpPort));
        } else if (request.apiRoot && isPlainHttp(request.apiRoot)) {
            argv.push('--usehttp');
        }
    }
    return argv;
}
