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

/**
 * TEA server information including URL, versions, and optional priority
 */

@Schema(name = "tea-server-info", description = "TEA server information including URL, versions, and optional priority")
@JsonTypeName("tea-server-info")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2026-03-29T10:44:15.267909500-04:00[America/Toronto]", comments = "Generator version: 7.21.0")
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

  @JsonProperty("rootUrl")
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

  @JsonProperty("versions")
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
  @DecimalMin(value = "0.0") @DecimalMax(value = "1.0") 
  @Schema(name = "priority", example = "0.8", description = "Optional priority for this server (0.0 to 1.0, where 1.0 is highest priority)", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("priority")
  public @Nullable Float getPriority() {
    return priority;
  }

  @JsonProperty("priority")
  public void setPriority(@Nullable Float priority) {
    this.priority = priority;
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
        Objects.equals(this.priority, teaServerInfo.priority);
  }

  @Override
  public int hashCode() {
    return Objects.hash(rootUrl, versions, priority);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class TeaTeaServerInfo {\n");
    sb.append("    rootUrl: ").append(toIndentedString(rootUrl)).append("\n");
    sb.append("    versions: ").append(toIndentedString(versions)).append("\n");
    sb.append("    priority: ").append(toIndentedString(priority)).append("\n");
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

