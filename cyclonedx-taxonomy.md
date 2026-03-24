# `reliza` CycloneDX Taxonomy

This is the namespace for CycloneDX properties used by [Reliza](https://reliza.io/) and particularly by [ReARM](https://rearmhq.com).

For details about the taxonomy, refer to the official CycloneDX Taxonomy Repository [here](https://github.com/CycloneDX/cyclonedx-property-taxonomy).

----

## reliza Namespace Taxonomy

| Property | Description |
|----------|-------------|
| `reliza:containerSafeVersion` | The variant of the release version that is safe to use as a container tag, i.e. if the full version is "1.2.3+metadata", this will be "1.2.3" |
| `reliza:rearmImport` | Namespace for properties used when importing CycloneDX components to ReARM |


## reliza:rearmImport Namespace Taxonomy

| Property | Description |
|----------|-------------|
| `reliza:rearmImport:rearmImportable` | Set to "true" if this comopnent can be imported to ReARM, otherwise it will be skipped on import |
| `reliza:rearmImport:componentName` | Component name to use on import (required) |
| `reliza:rearmImport:baseBranch` | Base branch of component, defaults to "main" |
| `reliza:rearmImport:vcsBranch` | VCS branch for this release, defaults to base branch |
| `reliza:rearmImport:vcsUri` | VCS Uri used for this component |
| `reliza:rearmImport:vcsPath` | Path for this component inside VCS repository, defaults to "." |
| `reliza:rearmImport:vcsTag` | VCS Tag for this release |
| `reliza:rearmImport:componentVersionSchema` | Component versioning schema |
| `reliza:rearmImport:branchVersionSchema` | Versioning schema for branches outside of base branch |