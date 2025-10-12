package io.reliza.model.tea;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import io.reliza.model.tea.TeaIdentifier;
import io.reliza.model.tea.TeaReleaseDistribution;
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
 * A TEA Component Release
 */

@Schema(name = "release", description = "A TEA Component Release")
@JsonTypeName("release")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2025-10-11T15:33:29.932635600-04:00[America/Toronto]", comments = "Generator version: 7.14.0")
public class TeaRelease {

  private UUID uuid;

  private @Nullable UUID component;

  private @Nullable String componentName;

  private String version;

  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
  private OffsetDateTime createdDate;

  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
  private @Nullable OffsetDateTime releaseDate;

  private @Nullable Boolean preRelease;

  @Valid
  private List<@Valid TeaIdentifier> identifiers = new ArrayList<>();

  @Valid
  private List<@Valid TeaReleaseDistribution> distributions = new ArrayList<>();

  public TeaRelease() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public TeaRelease(UUID uuid, String version, OffsetDateTime createdDate) {
    this.uuid = uuid;
    this.version = version;
    this.createdDate = createdDate;
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

  public TeaRelease component(@Nullable UUID component) {
    this.component = component;
    return this;
  }

  /**
   * A UUID
   * @return component
   */
  @Valid 
  @Schema(name = "component", description = "A UUID", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("component")
  public @Nullable UUID getComponent() {
    return component;
  }

  public void setComponent(@Nullable UUID component) {
    this.component = component;
  }

  public TeaRelease componentName(@Nullable String componentName) {
    this.componentName = componentName;
    return this;
  }

  /**
   * Name of the TEA Component this release belongs to
   * @return componentName
   */
  
  @Schema(name = "componentName", example = "tomcat", description = "Name of the TEA Component this release belongs to", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("componentName")
  public @Nullable String getComponentName() {
    return componentName;
  }

  public void setComponentName(@Nullable String componentName) {
    this.componentName = componentName;
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

  public TeaRelease createdDate(OffsetDateTime createdDate) {
    this.createdDate = createdDate;
    return this;
  }

  /**
   * Timestamp
   * @return createdDate
   */
  @NotNull @Valid @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$") 
  @Schema(name = "createdDate", example = "2024-03-20T15:30:00Z", description = "Timestamp", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("createdDate")
  public OffsetDateTime getCreatedDate() {
    return createdDate;
  }

  public void setCreatedDate(OffsetDateTime createdDate) {
    this.createdDate = createdDate;
  }

  public TeaRelease releaseDate(@Nullable OffsetDateTime releaseDate) {
    this.releaseDate = releaseDate;
    return this;
  }

  /**
   * Timestamp
   * @return releaseDate
   */
  @Valid @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$") 
  @Schema(name = "releaseDate", example = "2024-03-20T15:30:00Z", description = "Timestamp", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("releaseDate")
  public @Nullable OffsetDateTime getReleaseDate() {
    return releaseDate;
  }

  public void setReleaseDate(@Nullable OffsetDateTime releaseDate) {
    this.releaseDate = releaseDate;
  }

  public TeaRelease preRelease(@Nullable Boolean preRelease) {
    this.preRelease = preRelease;
    return this;
  }

  /**
   * A flag indicating pre-release (or beta) status. May be disabled after the creation of the release object, but can't be enabled after creation of an object. 
   * @return preRelease
   */
  
  @Schema(name = "preRelease", description = "A flag indicating pre-release (or beta) status. May be disabled after the creation of the release object, but can't be enabled after creation of an object. ", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("preRelease")
  public @Nullable Boolean getPreRelease() {
    return preRelease;
  }

  public void setPreRelease(@Nullable Boolean preRelease) {
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

  public TeaRelease distributions(List<@Valid TeaReleaseDistribution> distributions) {
    this.distributions = distributions;
    return this;
  }

  public TeaRelease addDistributionsItem(TeaReleaseDistribution distributionsItem) {
    if (this.distributions == null) {
      this.distributions = new ArrayList<>();
    }
    this.distributions.add(distributionsItem);
    return this;
  }

  /**
   * List of different formats of this component release
   * @return distributions
   */
  @Valid 
  @Schema(name = "distributions", description = "List of different formats of this component release", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("distributions")
  public List<@Valid TeaReleaseDistribution> getDistributions() {
    return distributions;
  }

  public void setDistributions(List<@Valid TeaReleaseDistribution> distributions) {
    this.distributions = distributions;
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
        Objects.equals(this.component, release.component) &&
        Objects.equals(this.componentName, release.componentName) &&
        Objects.equals(this.version, release.version) &&
        Objects.equals(this.createdDate, release.createdDate) &&
        Objects.equals(this.releaseDate, release.releaseDate) &&
        Objects.equals(this.preRelease, release.preRelease) &&
        Objects.equals(this.identifiers, release.identifiers) &&
        Objects.equals(this.distributions, release.distributions);
  }

  @Override
  public int hashCode() {
    return Objects.hash(uuid, component, componentName, version, createdDate, releaseDate, preRelease, identifiers, distributions);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class TeaRelease {\n");
    sb.append("    uuid: ").append(toIndentedString(uuid)).append("\n");
    sb.append("    component: ").append(toIndentedString(component)).append("\n");
    sb.append("    componentName: ").append(toIndentedString(componentName)).append("\n");
    sb.append("    version: ").append(toIndentedString(version)).append("\n");
    sb.append("    createdDate: ").append(toIndentedString(createdDate)).append("\n");
    sb.append("    releaseDate: ").append(toIndentedString(releaseDate)).append("\n");
    sb.append("    preRelease: ").append(toIndentedString(preRelease)).append("\n");
    sb.append("    identifiers: ").append(toIndentedString(identifiers)).append("\n");
    sb.append("    distributions: ").append(toIndentedString(distributions)).append("\n");
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

