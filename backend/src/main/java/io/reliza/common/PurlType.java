/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.common;

/**
 * Enum of all known purl types, as per https://github.com/package-url/purl-spec/blob/main/PURL-TYPES.rst
 */
public enum PurlType {
    ALPM("alpm"),
    APK("apk"),
    BITBUCKET("bitbucket"),
    BITNAMI("bitnami"),
    CARGO("cargo"),
    COCOAPODS("cocoapods"),
    COMPOSER("composer"),
    CONAN("conan"),
    CONDA("conda"),
    CPAN("cpan"),
    CRAN("cran"),
    DEB("deb"),
    DOCKER("docker"),
    GEM("gem"),
    GENERIC("generic"),
    GITHUB("github"),
    GOLANG("golang"),
    HACKAGE("hackage"),
    HEX("hex"),
    HUGGINGFACE("huggingface"),
    LUAROCKS("luarocks"),
    MAVEN("maven"),
    MLFLOW("mlflow"),
    NPM("npm"),
    NUGET("nuget"),
    OCI("oci"),
    PUB("pub"),
    PYPI("pypi"),
    QPKG("qpkg"),
    RPM("rpm"),
    SWIFT("swift"),
    SWID("swid");

    private final String purlString;

    PurlType(String purlString) {
        this.purlString = purlString;
    }

    public String getPurlString() {
        return purlString;
    }
}
