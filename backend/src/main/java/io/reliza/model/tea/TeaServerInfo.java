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

@Schema(name = "server-info", description = "TEA server information including URL, versions, and optional priority")
@JsonTypeName("server-info")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2026-10-08T02:50:49.497696677Z[Etc/UTC]", comments = "Generator version: 7.21.0")
public class TeaServerInfo {

  private URI rootUrl;

  @Valid
  private List<@Pattern(regexp = "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-((?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\\.(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?(?:\\+([0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?$")String> versions = new ArrayList<>();

  private Float priority = 1.0f;

  public TeaServerInfo() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public TeaServerInfo(URI rootUrl, List<@Pattern(regexp = "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-((?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\\.(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?(?:\\+([0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?$")String> versions) {
    this.rootUrl = rootUrl;
    this.versions = versions;
  }

  public TeaServerInfo rootUrl(URI rootUrl) {
    this.rootUrl = rootUrl;
    return this;
  }

  /**
   * Absolute API base URL for this TEA server, without trailing slash. Shall not include userinfo, query, or fragment. The scheme match is lowercase only (normalized form). Conforming deployments use lowercase `https`; `http` is allowed only so local-test documents still validate. 
   * @return rootUrl
   */
  @NotNull @Valid @Pattern(regexp = "^https?://[^/?#@]+(/[^?#\\s]*[^/?#\\s])?$") 
  @Schema(name = "rootUrl", example = "https://api.example.com", description = "Absolute API base URL for this TEA server, without trailing slash. Shall not include userinfo, query, or fragment. The scheme match is lowercase only (normalized form). Conforming deployments use lowercase `https`; `http` is allowed only so local-test documents still validate. ", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("rootUrl")
  public URI getRootUrl() {
    return rootUrl;
  }

  @JsonProperty("rootUrl")
  public void setRootUrl(URI rootUrl) {
    this.rootUrl = rootUrl;
  }

  public TeaServerInfo versions(List<@Pattern(regexp = "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-((?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\\.(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?(?:\\+([0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?$")String> versions) {
    this.versions = versions;
    return this;
  }

  public TeaServerInfo addVersionsItem(String versionsItem) {
    if (this.versions == null) {
      this.versions = new ArrayList<>();
    }
    this.versions.add(versionsItem);
    return this;
  }

  /**
   * Supported TEA API versions at this server, without a leading `v`. Each value is full SemVer 2.0.0 (MAJOR.MINOR.PATCH with optional prerelease and build metadata). Clients select the highest mutually supported version and append `/v` followed by that exact string. 
   * @return versions
   */
  @NotNull @Size(min = 1) 
  @Schema(name = "versions", example = "[1.0.0]", description = "Supported TEA API versions at this server, without a leading `v`. Each value is full SemVer 2.0.0 (MAJOR.MINOR.PATCH with optional prerelease and build metadata). Clients select the highest mutually supported version and append `/v` followed by that exact string. ", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("versions")
  public List<@Pattern(regexp = "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-((?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\\.(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?(?:\\+([0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?$")String> getVersions() {
    return versions;
  }

  @JsonProperty("versions")
  public void setVersions(List<@Pattern(regexp = "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-((?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\\.(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?(?:\\+([0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?$")String> versions) {
    this.versions = versions;
  }

  public TeaServerInfo priority(Float priority) {
    this.priority = priority;
    return this;
  }

  /**
   * Optional priority for this server (0.0 to 1.0, where 1.0 is highest priority). If omitted in the JSON response, clients shall treat the effective priority as 1.0 when ordering servers (the same rule as for `.well-known/tea` endpoint `priority`). 
   * minimum: 0.0
   * maximum: 1.0
   * @return priority
   */
  @DecimalMin(value = "0.0") @DecimalMax(value = "1.0") 
  @Schema(name = "priority", example = "0.8", description = "Optional priority for this server (0.0 to 1.0, where 1.0 is highest priority). If omitted in the JSON response, clients shall treat the effective priority as 1.0 when ordering servers (the same rule as for `.well-known/tea` endpoint `priority`). ", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("priority")
  public Float getPriority() {
    return priority;
  }

  @JsonProperty("priority")
  public void setPriority(Float priority) {
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
    TeaServerInfo serverInfo = (TeaServerInfo) o;
    return Objects.equals(this.rootUrl, serverInfo.rootUrl) &&
        Objects.equals(this.versions, serverInfo.versions) &&
        Objects.equals(this.priority, serverInfo.priority);
  }

  @Override
  public int hashCode() {
    return Objects.hash(rootUrl, versions, priority);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class TeaServerInfo {\n");
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

