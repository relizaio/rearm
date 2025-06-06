/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.tea;

import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.lang.Nullable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;


import jakarta.annotation.Generated;

/**
 * A collection of security-related documents
 */

@Schema(name = "collection", description = "A collection of security-related documents")
@JsonTypeName("collection")
@Generated(value = "io.reliza.codegen.languages.SpringCodegen", date = "2025-05-08T09:03:56.085827200-04:00[America/Toronto]", comments = "Generator version: 7.13.0")
public class TeaCollection {

  private @Nullable UUID uuid;

  private @Nullable Integer version;

  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
  private @Nullable OffsetDateTime date;

  private @Nullable TeaCollectionUpdateReason updateReason;

  @Valid
  private List<@Valid TeaArtifact> artifacts = new ArrayList<>();

  public TeaCollection uuid(UUID uuid) {
    this.uuid = uuid;
    return this;
  }

  /**
   * A UUID
   * @return uuid
   */
  @Valid 
  @Schema(name = "uuid", description = "A UUID", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("uuid")
  public UUID getUuid() {
    return uuid;
  }

  public void setUuid(UUID uuid) {
    this.uuid = uuid;
  }

  public TeaCollection version(Integer version) {
    this.version = version;
    return this;
  }

  /**
   * TEA Collection version, incremented each time its content changes. Versions start with 1. 
   * @return version
   */
  
  @Schema(name = "version", description = "TEA Collection version, incremented each time its content changes. Versions start with 1. ", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("version")
  public Integer getVersion() {
    return version;
  }

  public void setVersion(Integer version) {
    this.version = version;
  }

  public TeaCollection date(OffsetDateTime date) {
    this.date = date;
    return this;
  }

  /**
   * Timestamp
   * @return date
   */
  @Valid @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$") 
  @Schema(name = "date", example = "2024-03-20T15:30:00Z", description = "Timestamp", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("date")
  public OffsetDateTime getDate() {
    return date;
  }

  public void setDate(OffsetDateTime date) {
    this.date = date;
  }

  public TeaCollection updateReason(TeaCollectionUpdateReason updateReason) {
    this.updateReason = updateReason;
    return this;
  }

  /**
   * Reason for the update/release of the TEA Collection object.
   * @return updateReason
   */
  @Valid 
  @Schema(name = "updateReason", description = "Reason for the update/release of the TEA Collection object.", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("updateReason")
  public TeaCollectionUpdateReason getUpdateReason() {
    return updateReason;
  }

  public void setUpdateReason(TeaCollectionUpdateReason updateReason) {
    this.updateReason = updateReason;
  }

  public TeaCollection artifacts(List<@Valid TeaArtifact> artifacts) {
    this.artifacts = artifacts;
    return this;
  }

  public TeaCollection addArtifactsItem(TeaArtifact artifactsItem) {
    if (this.artifacts == null) {
      this.artifacts = new ArrayList<>();
    }
    this.artifacts.add(artifactsItem);
    return this;
  }

  /**
   * List of TEA artifact objects.
   * @return artifacts
   */
  @Valid 
  @Schema(name = "artifacts", description = "List of TEA artifact objects.", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("artifacts")
  public List<@Valid TeaArtifact> getArtifacts() {
    return artifacts;
  }

  public void setArtifacts(List<@Valid TeaArtifact> artifacts) {
    this.artifacts = artifacts;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TeaCollection collection = (TeaCollection) o;
    return Objects.equals(this.uuid, collection.uuid) &&
        Objects.equals(this.version, collection.version) &&
        Objects.equals(this.date, collection.date) &&
        Objects.equals(this.updateReason, collection.updateReason) &&
        Objects.equals(this.artifacts, collection.artifacts);
  }

  @Override
  public int hashCode() {
    return Objects.hash(uuid, version, date, updateReason, artifacts);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class Collection {\n");
    sb.append("    uuid: ").append(toIndentedString(uuid)).append("\n");
    sb.append("    version: ").append(toIndentedString(version)).append("\n");
    sb.append("    date: ").append(toIndentedString(date)).append("\n");
    sb.append("    updateReason: ").append(toIndentedString(updateReason)).append("\n");
    sb.append("    artifacts: ").append(toIndentedString(artifacts)).append("\n");
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

