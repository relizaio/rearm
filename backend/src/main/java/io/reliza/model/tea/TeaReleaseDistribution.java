package io.reliza.model.tea;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import io.reliza.model.tea.TeaChecksum;
import io.reliza.model.tea.TeaIdentifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.lang.Nullable;
import org.openapitools.jackson.nullable.JsonNullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * TeaReleaseDistribution
 */

@JsonTypeName("release-distribution")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2025-09-13T12:58:45.490102-04:00[America/Toronto]", comments = "Generator version: 7.14.0")
public class TeaReleaseDistribution {

  private @Nullable String distributionType;

  private @Nullable String description;

  @Valid
  private List<@Valid TeaIdentifier> identifiers = new ArrayList<>();

  private @Nullable String url;

  private @Nullable String signatureUrl;

  @Valid
  private List<@Valid TeaChecksum> checksums = new ArrayList<>();

  public TeaReleaseDistribution distributionType(@Nullable String distributionType) {
    this.distributionType = distributionType;
    return this;
  }

  /**
   * Unique identifier for the distribution type.
   * @return distributionType
   */
  
  @Schema(name = "distributionType", description = "Unique identifier for the distribution type.", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("distributionType")
  public @Nullable String getDistributionType() {
    return distributionType;
  }

  public void setDistributionType(@Nullable String distributionType) {
    this.distributionType = distributionType;
  }

  public TeaReleaseDistribution description(@Nullable String description) {
    this.description = description;
    return this;
  }

  /**
   * Free-text description of the distribution.
   * @return description
   */
  
  @Schema(name = "description", description = "Free-text description of the distribution.", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("description")
  public @Nullable String getDescription() {
    return description;
  }

  public void setDescription(@Nullable String description) {
    this.description = description;
  }

  public TeaReleaseDistribution identifiers(List<@Valid TeaIdentifier> identifiers) {
    this.identifiers = identifiers;
    return this;
  }

  public TeaReleaseDistribution addIdentifiersItem(TeaIdentifier identifiersItem) {
    if (this.identifiers == null) {
      this.identifiers = new ArrayList<>();
    }
    this.identifiers.add(identifiersItem);
    return this;
  }

  /**
   * List of identifiers specific to this distribution.
   * @return identifiers
   */
  @Valid 
  @Schema(name = "identifiers", description = "List of identifiers specific to this distribution.", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("identifiers")
  public List<@Valid TeaIdentifier> getIdentifiers() {
    return identifiers;
  }

  public void setIdentifiers(List<@Valid TeaIdentifier> identifiers) {
    this.identifiers = identifiers;
  }

  public TeaReleaseDistribution url(@Nullable String url) {
    this.url = url;
    return this;
  }

  /**
   * Direct download URL for the distribution.
   * @return url
   */
  
  @Schema(name = "url", description = "Direct download URL for the distribution.", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("url")
  public @Nullable String getUrl() {
    return url;
  }

  public void setUrl(@Nullable String url) {
    this.url = url;
  }

  public TeaReleaseDistribution signatureUrl(@Nullable String signatureUrl) {
    this.signatureUrl = signatureUrl;
    return this;
  }

  /**
   * Direct download URL for the distribution's external signature.
   * @return signatureUrl
   */
  
  @Schema(name = "signatureUrl", description = "Direct download URL for the distribution's external signature.", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("signatureUrl")
  public @Nullable String getSignatureUrl() {
    return signatureUrl;
  }

  public void setSignatureUrl(@Nullable String signatureUrl) {
    this.signatureUrl = signatureUrl;
  }

  public TeaReleaseDistribution checksums(List<@Valid TeaChecksum> checksums) {
    this.checksums = checksums;
    return this;
  }

  public TeaReleaseDistribution addChecksumsItem(TeaChecksum checksumsItem) {
    if (this.checksums == null) {
      this.checksums = new ArrayList<>();
    }
    this.checksums.add(checksumsItem);
    return this;
  }

  /**
   * List of checksums for the distribution.
   * @return checksums
   */
  @Valid 
  @Schema(name = "checksums", description = "List of checksums for the distribution.", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("checksums")
  public List<@Valid TeaChecksum> getChecksums() {
    return checksums;
  }

  public void setChecksums(List<@Valid TeaChecksum> checksums) {
    this.checksums = checksums;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TeaReleaseDistribution releaseDistribution = (TeaReleaseDistribution) o;
    return Objects.equals(this.distributionType, releaseDistribution.distributionType) &&
        Objects.equals(this.description, releaseDistribution.description) &&
        Objects.equals(this.identifiers, releaseDistribution.identifiers) &&
        Objects.equals(this.url, releaseDistribution.url) &&
        Objects.equals(this.signatureUrl, releaseDistribution.signatureUrl) &&
        Objects.equals(this.checksums, releaseDistribution.checksums);
  }

  @Override
  public int hashCode() {
    return Objects.hash(distributionType, description, identifiers, url, signatureUrl, checksums);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class TeaReleaseDistribution {\n");
    sb.append("    distributionType: ").append(toIndentedString(distributionType)).append("\n");
    sb.append("    description: ").append(toIndentedString(description)).append("\n");
    sb.append("    identifiers: ").append(toIndentedString(identifiers)).append("\n");
    sb.append("    url: ").append(toIndentedString(url)).append("\n");
    sb.append("    signatureUrl: ").append(toIndentedString(signatureUrl)).append("\n");
    sb.append("    checksums: ").append(toIndentedString(checksums)).append("\n");
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

