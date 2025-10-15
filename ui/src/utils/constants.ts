const EXTERNAL_PUBLIC_COMPONENTS_ORG = '00000000-0000-0000-0000-000000000000'

const PACKAGE_TYPES = ['MAVEN', 'NPM', 'NUGET', 'GEM', 'PYPI', 'CONTAINER']
const CDX_TYPES = [ 'APPLICATION', 'FRAMEWORK', 'LIBRARY', 'CONTAINER', 'PLATFORM', 'OPERATING_SYSTEM', 'DEVICE', 'DEVICE_DRIVER', 'FIRMWARE', 
    'FILE', 'MACHINE_LEARNING_MODEL', 'DATA', 'CRYPTOGRAPHIC_ASSET']
const OPERATING_SYSTEMS = ['WINDOWS', 'MACOS', 'LINUX', 'ANDROID', 'CHROMEOS', 'IOS', 'OTHER']
const CPU_ARCHITECTURES = ['AMD64', 'I386', 'PPC', 'ARMV7', 'ARMV8', 'IA32', 'MIPS', 'RISCV64', 'S390', 'S390X', 'OTHER']

const VERSION_TYPES = [
    {
        label: 'SemVer (Major.Minor.Patch-Modifier+Metadata)',
        value: 'semver'
    },
    {
        label: 'Ubuntu CalVer (YY.0M.Micro)',
        value: 'YY.0M.Micro'
    },
    {
        label: 'Youtube_dl CalVer (YYYY.0M.0D)',
        value: 'YYYY.0M.0D'
    },
    {
        label: 'Pytz CalVer (YYYY.MM)',
        value: 'YYYY.MM'
    },
    {
        label: 'Teradata CalVer (YY.MM.Minor.Micro)',
        value: 'YY.MM.Minor.Micro'
    },
    {
        label: 'Single Component (Major)',
        value: 'Major'
    },
    {
        label: 'Custom',
        value: 'custom_version'
    }
]

const LIFECYCLE_OPTIONS = [
    {label: 'Pending', key: 'PENDING'}, {label: 'Draft', key: 'DRAFT'}, {label: 'Assembled', key: 'ASSEMBLED'},
    {label: 'Shipped', key: 'GENERAL_AVAILABILITY'}, {label: 'Cancelled', key: 'CANCELLED'}, {label: 'Rejected', key: 'REJECTED'},
    {label: 'End of Support', key: 'END_OF_SUPPORT'}
]

enum TeaArtifactChecksumType {
    MD5,
    SHA_1,
    SHA_256,
    SHA_384,
    SHA_512,
    SHA3_256,
    SHA3_384,
    SHA3_512,
    BLAKE2B_256,
    BLAKE2B_384,
    BLAKE2B_512,
    BLAKE3,
}

const TEA_ARTIFACT_CHECKSUM_TYPES = [
    {value: 'MD5', label: 'MD5'},
    {value: 'SHA_1', label: 'SHA1'},
    {value: 'SHA_256', label: 'SHA_256'},
    {value: 'SHA_384', label: 'SHA_384'},
    {value: 'SHA_512', label: 'SHA_512'},
    {value: 'SHA3_256', label: 'SHA3_256'},
    {value: 'SHA3_384', label: 'SHA3_384'},
    {value: 'SHA3_512', label: 'SHA3_512'},
    {value: 'BLAKE2B_256', label: 'BLAKE2B_256'},
    {value: 'BLAKE2B_384', label: 'BLAKE2B_384'},
    {value: 'BLAKE2B_512', label: 'BLAKE2B_512'},
    {value: 'BLAKE3', label: 'BLAKE3'},
]

const CONTENT_TYPES = [
    {value: 'OCI', label: 'OCI'},
    {value: 'PLAIN_JSON', label: 'Plain JSON'},
    {value: 'OCTET_STREAM', label: 'Octet Stream'},
    {value: 'PLAIN_XML', label: 'Plain XML'},
]

export default {
    VersionTypes: VERSION_TYPES,
    ExternalPublicComponentsOrg: EXTERNAL_PUBLIC_COMPONENTS_ORG,
    SpawnInstancePermissionId: '00000000-0000-0000-0000-000000000002',
    RelizaRedirectLocalStorage: 'relizaRedirect',
    InstanceType: { 
        STANDALONE_INSTANCE: 'STANDALONE_INSTANCE',
        CLUSTER_INSTANCE: 'CLUSTER_INSTANCE',
        CLUSTER: 'CLUSTER'
    },
    ArtifactStoredIn: {
        REARM: 'REARM',
        EXTERNALLY: 'EXTERNALLY'
    },
    CdxTypes: CDX_TYPES.map((pt: string) => {return {label: pt, value: pt}}),
    PackageTypes: PACKAGE_TYPES.map((pt: string) => {return {label: pt, value: pt}}),
    OperatingSystems: OPERATING_SYSTEMS.map((pt: string) => {return {label: pt, value: pt}}),
    CpuArchitectures: CPU_ARCHITECTURES.map((pt: string) => {return {label: pt, value: pt}}),
    LifecycleOptions: LIFECYCLE_OPTIONS,
    TeaArtifactChecksumType,
    TeaArtifactChecksumTypes: TEA_ARTIFACT_CHECKSUM_TYPES,
    ContentTypes: CONTENT_TYPES
}