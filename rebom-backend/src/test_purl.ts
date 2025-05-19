const { PackageURL } = require('packageurl-js')
const purl1 = "pkg:oci/debian@sha256%3A244fd47e07d10?repository_url=docker.io/library/debian&arch=amd64&tag=latest"
console.log(PackageURL.fromString(purl1))

const purl2 = "pkg:oci/rebom-frontend@sha256:23434c383d8c096aaca9690ac27a20a444e61bbb69502f41dc1c85de2b0df0de?repository_url=registry.test.relizahub.com/487604fa-22eb-4b89-8e43-f55db4bbcf6e-public"
console.log(PackageURL.fromString(purl2))

const purl3 = "pkg:oci/rebom-frontend@sha256%3A23434c383d8c096aaca9690ac27a20a444e61bbb69502f41dc1c85de2b0df0de?repository_url=registry.test.relizahub.com/487604fa-22eb-4b89-8e43-f55db4bbcf6e-public"
console.log(PackageURL.fromString(purl3))
const purl4 = "pkg:oci/registry.test.relizahub.com/487604fa-22eb-4b89-8e43-f55db4bbcf6e-public/rebom-frontend@sha256:23434c383d8c096aaca9690ac27a20a444e61bbb69502f41dc1c85de2b0df0de"
console.log(PackageURL.fromString(purl4))