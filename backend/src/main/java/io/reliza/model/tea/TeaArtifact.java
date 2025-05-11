/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.tea;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import io.reliza.model.tea.TeaArtifactFormat;
import io.reliza.model.tea.TeaArtifactType;
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
@Generated(value = "io.reliza.codegen.languages.SpringCodegen", date = "2025-05-08T09:03:56.085827200-04:00[America/Toronto]", comments = "Generator version: 7.13.0")
public class TeaArtifact {

  private @Nullable UUID uuid;

  private @Nullable String name;

  private @Nullable TeaArtifactType type;

  @Valid
  private List<@Valid TeaArtifactFormat> formats = new ArrayList<>();

  public TeaArtifact uuid(UUID uuid) {
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

  public TeaArtifact name(String name) {
    this.name = name;
    return this;
  }

  /**
   * Artifact name
   * @return name
   */
  
  @Schema(name = "name", description = "Artifact name", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("name")
  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public TeaArtifact type(TeaArtifactType type) {
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
  public TeaArtifactType getType() {
    return type;
  }

  public void setType(TeaArtifactType type) {
    this.type = type;
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
        Objects.equals(this.formats, artifact.formats);
  }

  @Override
  public int hashCode() {
    return Objects.hash(uuid, name, type, formats);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class Artifact {\n");
    sb.append("    uuid: ").append(toIndentedString(uuid)).append("\n");
    sb.append("    name: ").append(toIndentedString(name)).append("\n");
    sb.append("    type: ").append(toIndentedString(type)).append("\n");
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

