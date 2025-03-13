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
    }
]

const LIFECYCLE_OPTIONS = [
    {label: 'Pending', key: 'PENDING'}, {label: 'Draft', key: 'DRAFT'}, {label: 'Assembled', key: 'ASSEMBLED'},
    {label: 'Shipped', key: 'GENERAL_AVAILABILITY'}, {label: 'Cancelled', key: 'CANCELLED'}, {label: 'Rejected', key: 'REJECTED'},
    {label: 'End of Support', key: 'END_OF_SUPPORT'}
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
    LifecycleOptions: LIFECYCLE_OPTIONS
}