/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.tea;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import io.reliza.model.tea.TeaIdentifier;
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
 * Release
 */

@JsonTypeName("release")
@Generated(value = "io.reliza.codegen.languages.SpringCodegen", date = "2025-05-08T09:03:56.085827200-04:00[America/Toronto]", comments = "Generator version: 7.13.0")
public class TeaRelease {

  private UUID uuid;

  private String version;

  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
  private OffsetDateTime releaseDate;

  private @Nullable Boolean preRelease;

  @Valid
  private List<@Valid TeaIdentifier> identifiers = new ArrayList<>();

  public TeaRelease() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public TeaRelease(UUID uuid, String version, OffsetDateTime releaseDate) {
    this.uuid = uuid;
    this.version = version;
    this.releaseDate = releaseDate;
  }

  public TeaRelease uuid(UUID uuid) {
    this.uuid = uuid;
    return this;
  }

  /**
   * A UUID
   * @return uuid
   */
  @NotNull @Valid 
  @Schema(name = "uuid", description = "A UUID", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("uuid")
  public UUID getUuid() {
    return uuid;
  }

  public void setUuid(UUID uuid) {
    this.uuid = uuid;
  }

  public TeaRelease version(String version) {
    this.version = version;
    return this;
  }

  /**
   * Version number
   * @return version
   */
  @NotNull 
  @Schema(name = "version", example = "1.2.3", description = "Version number", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("version")
  public String getVersion() {
    return version;
  }

  public void setVersion(String version) {
    this.version = version;
  }

  public TeaRelease releaseDate(OffsetDateTime releaseDate) {
    this.releaseDate = releaseDate;
    return this;
  }

  /**
   * Timestamp
   * @return releaseDate
   */
  @NotNull @Valid @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$") 
  @Schema(name = "releaseDate", example = "2024-03-20T15:30:00Z", description = "Timestamp", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("releaseDate")
  public OffsetDateTime getReleaseDate() {
    return releaseDate;
  }

  public void setReleaseDate(OffsetDateTime releaseDate) {
    this.releaseDate = releaseDate;
  }

  public TeaRelease preRelease(Boolean preRelease) {
    this.preRelease = preRelease;
    return this;
  }

  /**
   * A flag indicating pre-release (or beta) status. May be disabled after the creation of the release object, but can't be enabled after creation of an object. 
   * @return preRelease
   */
  
  @Schema(name = "preRelease", description = "A flag indicating pre-release (or beta) status. May be disabled after the creation of the release object, but can't be enabled after creation of an object. ", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("preRelease")
  public Boolean getPreRelease() {
    return preRelease;
  }

  public void setPreRelease(Boolean preRelease) {
    this.preRelease = preRelease;
  }

  public TeaRelease identifiers(List<@Valid TeaIdentifier> identifiers) {
    this.identifiers = identifiers;
    return this;
  }

  public TeaRelease addIdentifiersItem(TeaIdentifier identifiersItem) {
    if (this.identifiers == null) {
      this.identifiers = new ArrayList<>();
    }
    this.identifiers.add(identifiersItem);
    return this;
  }

  /**
   * List of identifiers for the component
   * @return identifiers
   */
  @Valid 
  @Schema(name = "identifiers", description = "List of identifiers for the component", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("identifiers")
  public List<@Valid TeaIdentifier> getIdentifiers() {
    return identifiers;
  }

  public void setIdentifiers(List<@Valid TeaIdentifier> identifiers) {
    this.identifiers = identifiers;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TeaRelease release = (TeaRelease) o;
    return Objects.equals(this.uuid, release.uuid) &&
        Objects.equals(this.version, release.version) &&
        Objects.equals(this.releaseDate, release.releaseDate) &&
        Objects.equals(this.preRelease, release.preRelease) &&
        Objects.equals(this.identifiers, release.identifiers);
  }

  @Override
  public int hashCode() {
    return Objects.hash(uuid, version, releaseDate, preRelease, identifiers);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class Release {\n");
    sb.append("    uuid: ").append(toIndentedString(uuid)).append("\n");
    sb.append("    version: ").append(toIndentedString(version)).append("\n");
    sb.append("    releaseDate: ").append(toIndentedString(releaseDate)).append("\n");
    sb.append("    preRelease: ").append(toIndentedString(preRelease)).append("\n");
    sb.append("    identifiers: ").append(toIndentedString(identifiers)).append("\n");
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

