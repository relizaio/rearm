package io.reliza.model.tea;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import io.reliza.model.tea.TeaChecksum;
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
 * A security-related document in a specific format
 */

@Schema(name = "artifact-format", description = "A security-related document in a specific format")
@JsonTypeName("artifact-format")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2026-03-29T10:44:15.267909500-04:00[America/Toronto]", comments = "Generator version: 7.21.0")
public class TeaArtifactFormat {

  private @Nullable String mediaType;

  private @Nullable String description;

  private @Nullable String url;

  private @Nullable String signatureUrl;

  @Valid
  private List<@Valid TeaChecksum> checksums = new ArrayList<>();

  public TeaArtifactFormat mediaType(@Nullable String mediaType) {
    this.mediaType = mediaType;
    return this;
  }

  /**
   * The Media Type of the document
   * @return mediaType
   */
  
  @Schema(name = "mediaType", description = "The Media Type of the document", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("mediaType")
  public @Nullable String getMediaType() {
    return mediaType;
  }

  @JsonProperty("mediaType")
  public void setMediaType(@Nullable String mediaType) {
    this.mediaType = mediaType;
  }

  public TeaArtifactFormat description(@Nullable String description) {
    this.description = description;
    return this;
  }

  /**
   * A free text describing the TEA Artifact
   * @return description
   */
  
  @Schema(name = "description", description = "A free text describing the TEA Artifact", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("description")
  public @Nullable String getDescription() {
    return description;
  }

  @JsonProperty("description")
  public void setDescription(@Nullable String description) {
    this.description = description;
  }

  public TeaArtifactFormat url(@Nullable String url) {
    this.url = url;
    return this;
  }

  /**
   * Direct download URL for the TEA Artifact
   * @return url
   */
  
  @Schema(name = "url", description = "Direct download URL for the TEA Artifact", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("url")
  public @Nullable String getUrl() {
    return url;
  }

  @JsonProperty("url")
  public void setUrl(@Nullable String url) {
    this.url = url;
  }

  public TeaArtifactFormat signatureUrl(@Nullable String signatureUrl) {
    this.signatureUrl = signatureUrl;
    return this;
  }

  /**
   * Direct download URL for an external signature of the TEA Artifact
   * @return signatureUrl
   */
  
  @Schema(name = "signatureUrl", description = "Direct download URL for an external signature of the TEA Artifact", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("signatureUrl")
  public @Nullable String getSignatureUrl() {
    return signatureUrl;
  }

  @JsonProperty("signatureUrl")
  public void setSignatureUrl(@Nullable String signatureUrl) {
    this.signatureUrl = signatureUrl;
  }

  public TeaArtifactFormat checksums(List<@Valid TeaChecksum> checksums) {
    this.checksums = checksums;
    return this;
  }

  public TeaArtifactFormat addChecksumsItem(TeaChecksum checksumsItem) {
    if (this.checksums == null) {
      this.checksums = new ArrayList<>();
    }
    this.checksums.add(checksumsItem);
    return this;
  }

  /**
   * List of checksums for the TEA Artifact
   * @return checksums
   */
  @Valid 
  @Schema(name = "checksums", description = "List of checksums for the TEA Artifact", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("checksums")
  public List<@Valid TeaChecksum> getChecksums() {
    return checksums;
  }

  @JsonProperty("checksums")
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
    TeaArtifactFormat artifactFormat = (TeaArtifactFormat) o;
    return Objects.equals(this.mediaType, artifactFormat.mediaType) &&
        Objects.equals(this.description, artifactFormat.description) &&
        Objects.equals(this.url, artifactFormat.url) &&
        Objects.equals(this.signatureUrl, artifactFormat.signatureUrl) &&
        Objects.equals(this.checksums, artifactFormat.checksums);
  }

  @Override
  public int hashCode() {
    return Objects.hash(mediaType, description, url, signatureUrl, checksums);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class TeaArtifactFormat {\n");
    sb.append("    mediaType: ").append(toIndentedString(mediaType)).append("\n");
    sb.append("    description: ").append(toIndentedString(description)).append("\n");
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
  private String toIndentedString(@Nullable Object o) {
    return o == null ? "null" : o.toString().replace("\n", "\n    ");
  }
}

