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
import org.springframework.lang.Nullable;
import org.openapitools.jackson.nullable.JsonNullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;


import java.util.*;
import jakarta.annotation.Generated;

import java.util.Map;
import java.util.HashMap;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
/**
 * TEA server information including URL, versions, and optional priority
 */

@Schema(name = "tea-server-info", description = "TEA server information including URL, versions, and optional priority")
@JsonTypeName("tea-server-info")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2025-10-15T13:35:56.249199300-04:00[America/Toronto]", comments = "Generator version: 7.14.0")
public class TeaTeaServerInfo {

  private URI rootUrl;

  @Valid
  private List<String> versions = new ArrayList<>();

  private @Nullable Float priority;

  public TeaTeaServerInfo() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public TeaTeaServerInfo(URI rootUrl, List<String> versions) {
    this.rootUrl = rootUrl;
    this.versions = versions;
  }

  public TeaTeaServerInfo rootUrl(URI rootUrl) {
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

  public TeaTeaServerInfo versions(List<String> versions) {
    this.versions = versions;
    return this;
  }

  public TeaTeaServerInfo addVersionsItem(String versionsItem) {
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

  public TeaTeaServerInfo priority(@Nullable Float priority) {
    this.priority = priority;
    return this;
  }

  /**
   * Optional priority for this server (0.0 to 1.0, where 1.0 is highest priority)
   * minimum: 0.0
   * maximum: 1.0
   * @return priority
   */
  @DecimalMin("0.0") @DecimalMax("1.0") 
  @Schema(name = "priority", example = "0.8", description = "Optional priority for this server (0.0 to 1.0, where 1.0 is highest priority)", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("priority")
  public @Nullable Float getPriority() {
    return priority;
  }

  public void setPriority(@Nullable Float priority) {
    this.priority = priority;
  }
    /**
    * A container for additional, undeclared properties.
    * This is a holder for any undeclared properties as specified with
    * the 'additionalProperties' keyword in the OAS document.
    */
    private Map<String, Object> additionalProperties;

    /**
    * Set the additional (undeclared) property with the specified name and value.
    * If the property does not already exist, create it otherwise replace it.
    */
    @JsonAnySetter
    public TeaTeaServerInfo putAdditionalProperty(String key, Object value) {
        if (this.additionalProperties == null) {
            this.additionalProperties = new HashMap<String, Object>();
        }
        this.additionalProperties.put(key, value);
        return this;
    }

    /**
    * Return the additional (undeclared) property.
    */
    @JsonAnyGetter
    public Map<String, Object> getAdditionalProperties() {
        return additionalProperties;
    }

    /**
    * Return the additional (undeclared) property with the specified name.
    */
    public Object getAdditionalProperty(String key) {
        if (this.additionalProperties == null) {
            return null;
        }
        return this.additionalProperties.get(key);
    }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TeaTeaServerInfo teaServerInfo = (TeaTeaServerInfo) o;
    return Objects.equals(this.rootUrl, teaServerInfo.rootUrl) &&
        Objects.equals(this.versions, teaServerInfo.versions) &&
        Objects.equals(this.priority, teaServerInfo.priority) &&
    Objects.equals(this.additionalProperties, teaServerInfo.additionalProperties);
  }

  @Override
  public int hashCode() {
    return Objects.hash(rootUrl, versions, priority, additionalProperties);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class TeaTeaServerInfo {\n");
    sb.append("    rootUrl: ").append(toIndentedString(rootUrl)).append("\n");
    sb.append("    versions: ").append(toIndentedString(versions)).append("\n");
    sb.append("    priority: ").append(toIndentedString(priority)).append("\n");
    
    sb.append("    additionalProperties: ").append(toIndentedString(additionalProperties)).append("\n");
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

