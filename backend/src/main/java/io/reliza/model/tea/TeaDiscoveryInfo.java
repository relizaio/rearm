package io.reliza.model.tea;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.lang.Nullable;
import org.openapitools.jackson.nullable.JsonNullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * Discovery information for a TEI
 */

@Schema(name = "discovery-info", description = "Discovery information for a TEI")
@JsonTypeName("discovery-info")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2025-10-11T15:33:29.932635600-04:00[America/Toronto]", comments = "Generator version: 7.14.0")
public class TeaDiscoveryInfo {

  private UUID productReleaseUuid;

  private URI rootUrl;

  @Valid
  private List<String> versions = new ArrayList<>();

  public TeaDiscoveryInfo() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public TeaDiscoveryInfo(UUID productReleaseUuid, URI rootUrl, List<String> versions) {
    this.productReleaseUuid = productReleaseUuid;
    this.rootUrl = rootUrl;
    this.versions = versions;
  }

  public TeaDiscoveryInfo productReleaseUuid(UUID productReleaseUuid) {
    this.productReleaseUuid = productReleaseUuid;
    return this;
  }

  /**
   * A UUID
   * @return productReleaseUuid
   */
  @NotNull @Valid 
  @Schema(name = "productReleaseUuid", description = "A UUID", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("productReleaseUuid")
  public UUID getProductReleaseUuid() {
    return productReleaseUuid;
  }

  public void setProductReleaseUuid(UUID productReleaseUuid) {
    this.productReleaseUuid = productReleaseUuid;
  }

  public TeaDiscoveryInfo rootUrl(URI rootUrl) {
    this.rootUrl = rootUrl;
    return this;
  }

  /**
   * Root URL of the TEA server for this TEI without trailing slash
   * @return rootUrl
   */
  @NotNull @Valid 
  @Schema(name = "rootUrl", example = "https://api.teaexample.com", description = "Root URL of the TEA server for this TEI without trailing slash", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("rootUrl")
  public URI getRootUrl() {
    return rootUrl;
  }

  public void setRootUrl(URI rootUrl) {
    this.rootUrl = rootUrl;
  }

  public TeaDiscoveryInfo versions(List<String> versions) {
    this.versions = versions;
    return this;
  }

  public TeaDiscoveryInfo addVersionsItem(String versionsItem) {
    if (this.versions == null) {
      this.versions = new ArrayList<>();
    }
    this.versions.add(versionsItem);
    return this;
  }

  /**
   * Supported TEA API versions at this server without v prefix
   * @return versions
   */
  @NotNull @Size(min = 1) 
  @Schema(name = "versions", example = "[0.2.0-beta.2, 1.0.0]", description = "Supported TEA API versions at this server without v prefix", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("versions")
  public List<String> getVersions() {
    return versions;
  }

  public void setVersions(List<String> versions) {
    this.versions = versions;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TeaDiscoveryInfo discoveryInfo = (TeaDiscoveryInfo) o;
    return Objects.equals(this.productReleaseUuid, discoveryInfo.productReleaseUuid) &&
        Objects.equals(this.rootUrl, discoveryInfo.rootUrl) &&
        Objects.equals(this.versions, discoveryInfo.versions);
  }

  @Override
  public int hashCode() {
    return Objects.hash(productReleaseUuid, rootUrl, versions);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class TeaDiscoveryInfo {\n");
    sb.append("    productReleaseUuid: ").append(toIndentedString(productReleaseUuid)).append("\n");
    sb.append("    rootUrl: ").append(toIndentedString(rootUrl)).append("\n");
    sb.append("    versions: ").append(toIndentedString(versions)).append("\n");
    sb.append("}");
    return sb.toString();
  }

  /**
   * Convert the given object to string with each line indented by 4 spaces
   * (except the first line).
   */
  private String toIndentedString(Object o) {
    if (o == null) {
      return "null";
    }
    return o.toString().replace("\n", "\n    ");
  }
}

