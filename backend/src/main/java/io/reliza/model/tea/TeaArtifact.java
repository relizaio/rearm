package io.reliza.model.tea;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.annotation.JsonValue;
import io.reliza.model.tea.TeaArtifactFormat;
import io.reliza.model.tea.TeaArtifactType;
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
 * A security-related document
 */

@Schema(name = "artifact", description = "A security-related document")
@JsonTypeName("artifact")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2026-03-29T10:44:15.267909500-04:00[America/Toronto]", comments = "Generator version: 7.21.0")
public class TeaArtifact {

  private UUID uuid;

  private Integer version = 1;

  private @Nullable String name;

  private TeaArtifactType type;

  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
  private @Nullable OffsetDateTime createdDate;

  @Valid
  private List<UUID> distributionIds = new ArrayList<>();

  @Valid
  private List<@Valid TeaArtifactFormat> formats = new ArrayList<>();

  public TeaArtifact() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public TeaArtifact(UUID uuid, TeaArtifactType type, List<@Valid TeaArtifactFormat> formats) {
    this.uuid = uuid;
    this.type = type;
    this.formats = formats;
  }

  public TeaArtifact uuid(UUID uuid) {
    this.uuid = uuid;
    return this;
  }

  /**
   * The UUID of the TEA Artifact object. Together with *version* uniquely identifies the TEA Artifact.
   * @return uuid
   */
  @NotNull @Valid 
  @Schema(name = "uuid", description = "The UUID of the TEA Artifact object. Together with *version* uniquely identifies the TEA Artifact.", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("uuid")
  public UUID getUuid() {
    return uuid;
  }

  @JsonProperty("uuid")
  public void setUuid(UUID uuid) {
    this.uuid = uuid;
  }

  public TeaArtifact version(Integer version) {
    this.version = version;
    return this;
  }

  /**
   * An integer with default value 1. Together with *uuid* uniquely identifies the TEA Artifact. This field can be used to designate successive, immutable revisions of an artefact content (e.g. an updated VEX file). 
   * @return version
   */
  
  @Schema(name = "version", description = "An integer with default value 1. Together with *uuid* uniquely identifies the TEA Artifact. This field can be used to designate successive, immutable revisions of an artefact content (e.g. an updated VEX file). ", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("version")
  public Integer getVersion() {
    return version;
  }

  @JsonProperty("version")
  public void setVersion(Integer version) {
    this.version = version;
  }

  public TeaArtifact name(@Nullable String name) {
    this.name = name;
    return this;
  }

  /**
   * Name of TEA Artifact
   * @return name
   */
  
  @Schema(name = "name", description = "Name of TEA Artifact", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("name")
  public @Nullable String getName() {
    return name;
  }

  @JsonProperty("name")
  public void setName(@Nullable String name) {
    this.name = name;
  }

  public TeaArtifact type(TeaArtifactType type) {
    this.type = type;
    return this;
  }

  /**
   * Type of TEA Artifact
   * @return type
   */
  @NotNull @Valid 
  @Schema(name = "type", description = "Type of TEA Artifact", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("type")
  public TeaArtifactType getType() {
    return type;
  }

  @JsonProperty("type")
  public void setType(TeaArtifactType type) {
    this.type = type;
  }

  public TeaArtifact createdDate(@Nullable OffsetDateTime createdDate) {
    this.createdDate = createdDate;
    return this;
  }

  /**
   * The date when the TEA Artifact revision was created.
   * @return createdDate
   */
  @Valid @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$") 
  @Schema(name = "createdDate", example = "2024-03-20T15:30:00Z", description = "The date when the TEA Artifact revision was created.", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("createdDate")
  public @Nullable OffsetDateTime getCreatedDate() {
    return createdDate;
  }

  @JsonProperty("createdDate")
  public void setCreatedDate(@Nullable OffsetDateTime createdDate) {
    this.createdDate = createdDate;
  }

  public TeaArtifact distributionIds(List<UUID> distributionIds) {
    this.distributionIds = distributionIds;
    return this;
  }

  public TeaArtifact addDistributionIdsItem(UUID distributionIdsItem) {
    if (this.distributionIds == null) {
      this.distributionIds = new ArrayList<>();
    }
    this.distributionIds.add(distributionIdsItem);
    return this;
  }

  /**
   * List of TEA Component Release distributions that this TEA Artifact applies to. If absent or empty, the TEA Artifact applies to all distributions. 
   * @return distributionIds
   */
  @Valid 
  @Schema(name = "distributionIds", description = "List of TEA Component Release distributions that this TEA Artifact applies to. If absent or empty, the TEA Artifact applies to all distributions. ", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("distributionIds")
  public List<UUID> getDistributionIds() {
    return distributionIds;
  }

  @JsonProperty("distributionIds")
  public void setDistributionIds(List<UUID> distributionIds) {
    this.distributionIds = distributionIds;
  }

  public TeaArtifact formats(List<@Valid TeaArtifactFormat> formats) {
    this.formats = formats;
    return this;
  }

  public TeaArtifact addFormatsItem(TeaArtifactFormat formatsItem) {
    if (this.formats == null) {
      this.formats = new ArrayList<>();
    }
    this.formats.add(formatsItem);
    return this;
  }

  /**
   * List of objects with the same content, but in different formats. The order of the list has no significance. 
   * @return formats
   */
  @NotNull @Valid 
  @Schema(name = "formats", description = "List of objects with the same content, but in different formats. The order of the list has no significance. ", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("formats")
  public List<@Valid TeaArtifactFormat> getFormats() {
    return formats;
  }

  @JsonProperty("formats")
  public void setFormats(List<@Valid TeaArtifactFormat> formats) {
    this.formats = formats;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TeaArtifact artifact = (TeaArtifact) o;
    return Objects.equals(this.uuid, artifact.uuid) &&
        Objects.equals(this.version, artifact.version) &&
        Objects.equals(this.name, artifact.name) &&
        Objects.equals(this.type, artifact.type) &&
        Objects.equals(this.createdDate, artifact.createdDate) &&
        Objects.equals(this.distributionIds, artifact.distributionIds) &&
        Objects.equals(this.formats, artifact.formats);
  }

  @Override
  public int hashCode() {
    return Objects.hash(uuid, version, name, type, createdDate, distributionIds, formats);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class TeaArtifact {\n");
    sb.append("    uuid: ").append(toIndentedString(uuid)).append("\n");
    sb.append("    version: ").append(toIndentedString(version)).append("\n");
    sb.append("    name: ").append(toIndentedString(name)).append("\n");
    sb.append("    type: ").append(toIndentedString(type)).append("\n");
    sb.append("    createdDate: ").append(toIndentedString(createdDate)).append("\n");
    sb.append("    distributionIds: ").append(toIndentedString(distributionIds)).append("\n");
    sb.append("    formats: ").append(toIndentedString(formats)).append("\n");
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

