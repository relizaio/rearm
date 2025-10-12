package io.reliza.model.tea;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.annotation.JsonValue;
import io.reliza.model.tea.TeaArtifactFormat;
import io.reliza.model.tea.TeaArtifactType;
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
 * A security-related document
 */

@Schema(name = "artifact", description = "A security-related document")
@JsonTypeName("artifact")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2025-10-11T15:33:29.932635600-04:00[America/Toronto]", comments = "Generator version: 7.14.0")
public class TeaArtifact {

  private @Nullable UUID uuid;

  private @Nullable String name;

  private @Nullable TeaArtifactType type;

  @Valid
  private List<String> distributionTypes = new ArrayList<>();

  @Valid
  private List<@Valid TeaArtifactFormat> formats = new ArrayList<>();

  public TeaArtifact uuid(@Nullable UUID uuid) {
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
  public @Nullable UUID getUuid() {
    return uuid;
  }

  public void setUuid(@Nullable UUID uuid) {
    this.uuid = uuid;
  }

  public TeaArtifact name(@Nullable String name) {
    this.name = name;
    return this;
  }

  /**
   * Artifact name
   * @return name
   */
  
  @Schema(name = "name", description = "Artifact name", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("name")
  public @Nullable String getName() {
    return name;
  }

  public void setName(@Nullable String name) {
    this.name = name;
  }

  public TeaArtifact type(@Nullable TeaArtifactType type) {
    this.type = type;
    return this;
  }

  /**
   * Type of artifact
   * @return type
   */
  @Valid 
  @Schema(name = "type", description = "Type of artifact", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("type")
  public @Nullable TeaArtifactType getType() {
    return type;
  }

  public void setType(@Nullable TeaArtifactType type) {
    this.type = type;
  }

  public TeaArtifact distributionTypes(List<String> distributionTypes) {
    this.distributionTypes = distributionTypes;
    return this;
  }

  public TeaArtifact addDistributionTypesItem(String distributionTypesItem) {
    if (this.distributionTypes == null) {
      this.distributionTypes = new ArrayList<>();
    }
    this.distributionTypes.add(distributionTypesItem);
    return this;
  }

  /**
   * List of component distributions types that this artifact applies to. If absent, the artifact applies to all distributions. 
   * @return distributionTypes
   */
  
  @Schema(name = "distributionTypes", description = "List of component distributions types that this artifact applies to. If absent, the artifact applies to all distributions. ", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("distributionTypes")
  public List<String> getDistributionTypes() {
    return distributionTypes;
  }

  public void setDistributionTypes(List<String> distributionTypes) {
    this.distributionTypes = distributionTypes;
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
  @Valid 
  @Schema(name = "formats", description = "List of objects with the same content, but in different formats. The order of the list has no significance. ", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("formats")
  public List<@Valid TeaArtifactFormat> getFormats() {
    return formats;
  }

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
        Objects.equals(this.name, artifact.name) &&
        Objects.equals(this.type, artifact.type) &&
        Objects.equals(this.distributionTypes, artifact.distributionTypes) &&
        Objects.equals(this.formats, artifact.formats);
  }

  @Override
  public int hashCode() {
    return Objects.hash(uuid, name, type, distributionTypes, formats);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class TeaArtifact {\n");
    sb.append("    uuid: ").append(toIndentedString(uuid)).append("\n");
    sb.append("    name: ").append(toIndentedString(name)).append("\n");
    sb.append("    type: ").append(toIndentedString(type)).append("\n");
    sb.append("    distributionTypes: ").append(toIndentedString(distributionTypes)).append("\n");
    sb.append("    formats: ").append(toIndentedString(formats)).append("\n");
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

