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
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2025-09-13T12:58:45.490102-04:00[America/Toronto]", comments = "Generator version: 7.14.0")
public class TeaArtifactFormat {

  private @Nullable String mimeType;

  private @Nullable String description;

  private @Nullable String url;

  private @Nullable String signatureUrl;

  @Valid
  private List<@Valid TeaChecksum> checksums = new ArrayList<>();

  public TeaArtifactFormat mimeType(@Nullable String mimeType) {
    this.mimeType = mimeType;
    return this;
  }

  /**
   * The MIME type of the document
   * @return mimeType
   */
  
  @Schema(name = "mimeType", description = "The MIME type of the document", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("mimeType")
  public @Nullable String getMimeType() {
    return mimeType;
  }

  public void setMimeType(@Nullable String mimeType) {
    this.mimeType = mimeType;
  }

  public TeaArtifactFormat description(@Nullable String description) {
    this.description = description;
    return this;
  }

  /**
   * A free text describing the artifact
   * @return description
   */
  
  @Schema(name = "description", description = "A free text describing the artifact", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("description")
  public @Nullable String getDescription() {
    return description;
  }

  public void setDescription(@Nullable String description) {
    this.description = description;
  }

  public TeaArtifactFormat url(@Nullable String url) {
    this.url = url;
    return this;
  }

  /**
   * Direct download URL for the artifact
   * @return url
   */
  
  @Schema(name = "url", description = "Direct download URL for the artifact", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("url")
  public @Nullable String getUrl() {
    return url;
  }

  public void setUrl(@Nullable String url) {
    this.url = url;
  }

  public TeaArtifactFormat signatureUrl(@Nullable String signatureUrl) {
    this.signatureUrl = signatureUrl;
    return this;
  }

  /**
   * Direct download URL for an external signature of the artifact
   * @return signatureUrl
   */
  
  @Schema(name = "signatureUrl", description = "Direct download URL for an external signature of the artifact", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("signatureUrl")
  public @Nullable String getSignatureUrl() {
    return signatureUrl;
  }

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
   * List of checksums for the artifact
   * @return checksums
   */
  @Valid 
  @Schema(name = "checksums", description = "List of checksums for the artifact", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
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
    TeaArtifactFormat artifactFormat = (TeaArtifactFormat) o;
    return Objects.equals(this.mimeType, artifactFormat.mimeType) &&
        Objects.equals(this.description, artifactFormat.description) &&
        Objects.equals(this.url, artifactFormat.url) &&
        Objects.equals(this.signatureUrl, artifactFormat.signatureUrl) &&
        Objects.equals(this.checksums, artifactFormat.checksums);
  }

  @Override
  public int hashCode() {
    return Objects.hash(mimeType, description, url, signatureUrl, checksums);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class TeaArtifactFormat {\n");
    sb.append("    mimeType: ").append(toIndentedString(mimeType)).append("\n");
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
  private String toIndentedString(Object o) {
    if (o == null) {
      return "null";
    }
    return o.toString().replace("\n", "\n    ");
  }
}

