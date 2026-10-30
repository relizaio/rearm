package io.reliza.model.tea;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.annotation.JsonValue;
import io.reliza.model.tea.TeaArtifact;
import io.reliza.model.tea.TeaCollectionBelongsToType;
import io.reliza.model.tea.TeaCollectionUpdateReason;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.lang.Nullable;
import org.openapitools.jackson.nullable.JsonNullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * A collection of security-related documents.  Every component release and product release has a collection. If no artifacts have been published when the release first becomes retrievable, the server serves version 1 with an empty &#x60;artifacts&#x60; list and &#x60;updateReason.type: INITIAL_RELEASE&#x60;. That version is immutable like any other; the first artifacts are published as version 2. 
 */

@Schema(name = "collection", description = "A collection of security-related documents.  Every component release and product release has a collection. If no artifacts have been published when the release first becomes retrievable, the server serves version 1 with an empty `artifacts` list and `updateReason.type: INITIAL_RELEASE`. That version is immutable like any other; the first artifacts are published as version 2. ")
@JsonTypeName("collection")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2026-10-08T02:50:49.497696677Z[Etc/UTC]", comments = "Generator version: 7.21.0")
public class TeaCollection {

  private UUID uuid;

  private Integer version;

  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
  private OffsetDateTime createdDate;

  private TeaCollectionBelongsToType belongsTo;

  private TeaCollectionUpdateReason updateReason;

  @Valid
  private List<@Valid TeaArtifact> artifacts = new ArrayList<>();

  public TeaCollection() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public TeaCollection(UUID uuid, Integer version, OffsetDateTime createdDate, TeaCollectionBelongsToType belongsTo, TeaCollectionUpdateReason updateReason, List<@Valid TeaArtifact> artifacts) {
    this.uuid = uuid;
    this.version = version;
    this.createdDate = createdDate;
    this.belongsTo = belongsTo;
    this.updateReason = updateReason;
    this.artifacts = artifacts;
  }

  public TeaCollection uuid(UUID uuid) {
    this.uuid = uuid;
    return this;
  }

  /**
   * UUID of the TEA Collection object. A TEA Collection shall use the same UUID as its parent Product Release or Component Release. Within an API base, a Product Release and a Component Release shall not share a UUID. Consequently, Collections belonging to different parent releases have different UUIDs. Versions of the same Collection retain the same UUID. See `doc/tea-uuid-scope.md`. 
   * @return uuid
   */
  @NotNull @Valid 
  @Schema(name = "uuid", description = "UUID of the TEA Collection object. A TEA Collection shall use the same UUID as its parent Product Release or Component Release. Within an API base, a Product Release and a Component Release shall not share a UUID. Consequently, Collections belonging to different parent releases have different UUIDs. Versions of the same Collection retain the same UUID. See `doc/tea-uuid-scope.md`. ", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("uuid")
  public UUID getUuid() {
    return uuid;
  }

  @JsonProperty("uuid")
  public void setUuid(UUID uuid) {
    this.uuid = uuid;
  }

  public TeaCollection version(Integer version) {
    this.version = version;
    return this;
  }

  /**
   * TEA Collection version, incremented by 1 each time its content changes. Versions start with 1. Content changes include replacing an embedded artifact with a newer revision (for example one published because an external `url` or `signatureUrl` changed). If a Collection adopts that new artifact revision, the server shall publish a new Collection version. Previously published Collection versions shall remain unchanged. Classification of that adoption follows `collection-update-reason-type`. 
   * @return version
   */
  @NotNull 
  @Schema(name = "version", description = "TEA Collection version, incremented by 1 each time its content changes. Versions start with 1. Content changes include replacing an embedded artifact with a newer revision (for example one published because an external `url` or `signatureUrl` changed). If a Collection adopts that new artifact revision, the server shall publish a new Collection version. Previously published Collection versions shall remain unchanged. Classification of that adoption follows `collection-update-reason-type`. ", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("version")
  public Integer getVersion() {
    return version;
  }

  @JsonProperty("version")
  public void setVersion(Integer version) {
    this.version = version;
  }

  public TeaCollection createdDate(OffsetDateTime createdDate) {
    this.createdDate = createdDate;
    return this;
  }

  /**
   * The date when the TEA Collection version was created.
   * @return createdDate
   */
  @NotNull @Valid @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$") 
  @Schema(name = "createdDate", example = "2024-03-20T15:30:00Z", description = "The date when the TEA Collection version was created.", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("createdDate")
  public OffsetDateTime getCreatedDate() {
    return createdDate;
  }

  @JsonProperty("createdDate")
  public void setCreatedDate(OffsetDateTime createdDate) {
    this.createdDate = createdDate;
  }

  public TeaCollection belongsTo(TeaCollectionBelongsToType belongsTo) {
    this.belongsTo = belongsTo;
    return this;
  }

  /**
   * Indicates whether this collection belongs to a Component Release or a Product Release
   * @return belongsTo
   */
  @NotNull @Valid 
  @Schema(name = "belongsTo", description = "Indicates whether this collection belongs to a Component Release or a Product Release", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("belongsTo")
  public TeaCollectionBelongsToType getBelongsTo() {
    return belongsTo;
  }

  @JsonProperty("belongsTo")
  public void setBelongsTo(TeaCollectionBelongsToType belongsTo) {
    this.belongsTo = belongsTo;
  }

  public TeaCollection updateReason(TeaCollectionUpdateReason updateReason) {
    this.updateReason = updateReason;
    return this;
  }

  /**
   * Reason for the update/release of the TEA Collection object.
   * @return updateReason
   */
  @NotNull @Valid 
  @Schema(name = "updateReason", description = "Reason for the update/release of the TEA Collection object.", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("updateReason")
  public TeaCollectionUpdateReason getUpdateReason() {
    return updateReason;
  }

  @JsonProperty("updateReason")
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
   * Array of TEA Artifact objects (may be empty).
   * @return artifacts
   */
  @NotNull @Valid 
  @Schema(name = "artifacts", description = "Array of TEA Artifact objects (may be empty).", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("artifacts")
  public List<@Valid TeaArtifact> getArtifacts() {
    return artifacts;
  }

  @JsonProperty("artifacts")
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
        Objects.equals(this.createdDate, collection.createdDate) &&
        Objects.equals(this.belongsTo, collection.belongsTo) &&
        Objects.equals(this.updateReason, collection.updateReason) &&
        Objects.equals(this.artifacts, collection.artifacts);
  }

  @Override
  public int hashCode() {
    return Objects.hash(uuid, version, createdDate, belongsTo, updateReason, artifacts);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class TeaCollection {\n");
    sb.append("    uuid: ").append(toIndentedString(uuid)).append("\n");
    sb.append("    version: ").append(toIndentedString(version)).append("\n");
    sb.append("    createdDate: ").append(toIndentedString(createdDate)).append("\n");
    sb.append("    belongsTo: ").append(toIndentedString(belongsTo)).append("\n");
    sb.append("    updateReason: ").append(toIndentedString(updateReason)).append("\n");
    sb.append("    artifacts: ").append(toIndentedString(artifacts)).append("\n");
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

