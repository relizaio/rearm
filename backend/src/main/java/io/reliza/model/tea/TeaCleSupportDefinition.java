package io.reliza.model.tea;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import java.net.URI;
import org.springframework.lang.Nullable;
import org.openapitools.jackson.nullable.JsonNullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * A support policy definition from CLE
 */

@Schema(name = "cle-support-definition", description = "A support policy definition from CLE")
@JsonTypeName("cle-support-definition")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2026-03-29T10:44:15.267909500-04:00[America/Toronto]", comments = "Generator version: 7.21.0")
public class TeaCleSupportDefinition {

  private String id;

  private String description;

  private @Nullable URI url;

  public TeaCleSupportDefinition() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public TeaCleSupportDefinition(String id, String description) {
    this.id = id;
    this.description = description;
  }

  public TeaCleSupportDefinition id(String id) {
    this.id = id;
    return this;
  }

  /**
   * Unique identifier for the support policy
   * @return id
   */
  @NotNull 
  @Schema(name = "id", description = "Unique identifier for the support policy", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("id")
  public String getId() {
    return id;
  }

  @JsonProperty("id")
  public void setId(String id) {
    this.id = id;
  }

  public TeaCleSupportDefinition description(String description) {
    this.description = description;
    return this;
  }

  /**
   * Human-readable description of the policy
   * @return description
   */
  @NotNull 
  @Schema(name = "description", description = "Human-readable description of the policy", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("description")
  public String getDescription() {
    return description;
  }

  @JsonProperty("description")
  public void setDescription(String description) {
    this.description = description;
  }

  public TeaCleSupportDefinition url(@Nullable URI url) {
    this.url = url;
    return this;
  }

  /**
   * URL to detailed documentation about this support policy
   * @return url
   */
  @Valid 
  @Schema(name = "url", description = "URL to detailed documentation about this support policy", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("url")
  public @Nullable URI getUrl() {
    return url;
  }

  @JsonProperty("url")
  public void setUrl(@Nullable URI url) {
    this.url = url;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TeaCleSupportDefinition cleSupportDefinition = (TeaCleSupportDefinition) o;
    return Objects.equals(this.id, cleSupportDefinition.id) &&
        Objects.equals(this.description, cleSupportDefinition.description) &&
        Objects.equals(this.url, cleSupportDefinition.url);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, description, url);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class TeaCleSupportDefinition {\n");
    sb.append("    id: ").append(toIndentedString(id)).append("\n");
    sb.append("    description: ").append(toIndentedString(description)).append("\n");
    sb.append("    url: ").append(toIndentedString(url)).append("\n");
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

