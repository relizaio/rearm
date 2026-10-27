package io.reliza.model.tea;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import io.reliza.model.tea.TeaCollection;
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
 * A TEA Component Release together with its latest collection.  Extends &#x60;component-release&#x60; with a &#x60;latestCollection&#x60; property; every field of &#x60;component-release&#x60; applies unchanged. 
 */

@Schema(name = "component-release-with-collection", description = "A TEA Component Release together with its latest collection.  Extends `component-release` with a `latestCollection` property; every field of `component-release` applies unchanged. ")
@JsonTypeName("component-release-with-collection")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2026-10-08T02:50:49.497696677Z[Etc/UTC]", comments = "Generator version: 7.21.0")
public class TeaComponentReleaseWithCollection {

  private UUID uuid;

  private UUID component;

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

  private TeaCollection latestCollection;

  public TeaComponentReleaseWithCollection() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public TeaComponentReleaseWithCollection(UUID uuid, UUID component, String version, OffsetDateTime createdDate, TeaCollection latestCollection) {
    this.uuid = uuid;
    this.component = component;
    this.version = version;
    this.createdDate = createdDate;
    this.latestCollection = latestCollection;
  }

  public TeaComponentReleaseWithCollection uuid(UUID uuid) {
    this.uuid = uuid;
    return this;
  }

  /**
   * A unique identifier of the TEA Component Release. Within an API base it shall not also identify a Product Release; see `doc/tea-uuid-scope.md`. 
   * @return uuid
   */
  @NotNull @Valid 
  @Schema(name = "uuid", description = "A unique identifier of the TEA Component Release. Within an API base it shall not also identify a Product Release; see `doc/tea-uuid-scope.md`. ", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("uuid")
  public UUID getUuid() {
    return uuid;
  }

  @JsonProperty("uuid")
  public void setUuid(UUID uuid) {
    this.uuid = uuid;
  }

  public TeaComponentReleaseWithCollection component(UUID component) {
    this.component = component;
    return this;
  }

  /**
   * UUID of the TEA Component this release belongs to
   * @return component
   */
  @NotNull @Valid 
  @Schema(name = "component", description = "UUID of the TEA Component this release belongs to", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("component")
  public UUID getComponent() {
    return component;
  }

  @JsonProperty("component")
  public void setComponent(UUID component) {
    this.component = component;
  }

  public TeaComponentReleaseWithCollection componentName(@Nullable String componentName) {
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

  @JsonProperty("componentName")
  public void setComponentName(@Nullable String componentName) {
    this.componentName = componentName;
  }

  public TeaComponentReleaseWithCollection version(String version) {
    this.version = version;
    return this;
  }

  /**
   * Human-readable version string
   * @return version
   */
  @NotNull 
  @Schema(name = "version", example = "1.2.3", description = "Human-readable version string", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("version")
  public String getVersion() {
    return version;
  }

  @JsonProperty("version")
  public void setVersion(String version) {
    this.version = version;
  }

  public TeaComponentReleaseWithCollection createdDate(OffsetDateTime createdDate) {
    this.createdDate = createdDate;
    return this;
  }

  /**
   * Timestamp when this Release was created in TEA (for sorting purposes)
   * @return createdDate
   */
  @NotNull @Valid @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$") 
  @Schema(name = "createdDate", example = "2024-03-20T15:30:00Z", description = "Timestamp when this Release was created in TEA (for sorting purposes)", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("createdDate")
  public OffsetDateTime getCreatedDate() {
    return createdDate;
  }

  @JsonProperty("createdDate")
  public void setCreatedDate(OffsetDateTime createdDate) {
    this.createdDate = createdDate;
  }

  public TeaComponentReleaseWithCollection releaseDate(@Nullable OffsetDateTime releaseDate) {
    this.releaseDate = releaseDate;
    return this;
  }

  /**
   * Timestamp of the release
   * @return releaseDate
   */
  @Valid @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$") 
  @Schema(name = "releaseDate", example = "2024-03-20T15:30:00Z", description = "Timestamp of the release", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("releaseDate")
  public @Nullable OffsetDateTime getReleaseDate() {
    return releaseDate;
  }

  @JsonProperty("releaseDate")
  public void setReleaseDate(@Nullable OffsetDateTime releaseDate) {
    this.releaseDate = releaseDate;
  }

  public TeaComponentReleaseWithCollection preRelease(@Nullable Boolean preRelease) {
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

  @JsonProperty("preRelease")
  public void setPreRelease(@Nullable Boolean preRelease) {
    this.preRelease = preRelease;
  }

  public TeaComponentReleaseWithCollection identifiers(List<@Valid TeaIdentifier> identifiers) {
    this.identifiers = identifiers;
    return this;
  }

  public TeaComponentReleaseWithCollection addIdentifiersItem(TeaIdentifier identifiersItem) {
    if (this.identifiers == null) {
      this.identifiers = new ArrayList<>();
    }
    this.identifiers.add(identifiersItem);
    return this;
  }

  /**
   * Array of identifiers for the component release
   * @return identifiers
   */
  @Valid 
  @Schema(name = "identifiers", description = "Array of identifiers for the component release", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("identifiers")
  public List<@Valid TeaIdentifier> getIdentifiers() {
    return identifiers;
  }

  @JsonProperty("identifiers")
  public void setIdentifiers(List<@Valid TeaIdentifier> identifiers) {
    this.identifiers = identifiers;
  }

  public TeaComponentReleaseWithCollection distributions(List<@Valid TeaReleaseDistribution> distributions) {
    this.distributions = distributions;
    return this;
  }

  public TeaComponentReleaseWithCollection addDistributionsItem(TeaReleaseDistribution distributionsItem) {
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

  @JsonProperty("distributions")
  public void setDistributions(List<@Valid TeaReleaseDistribution> distributions) {
    this.distributions = distributions;
  }

  public TeaComponentReleaseWithCollection latestCollection(TeaCollection latestCollection) {
    this.latestCollection = latestCollection;
    return this;
  }

  /**
   * The latest TEA Collection for this component release
   * @return latestCollection
   */
  @NotNull @Valid 
  @Schema(name = "latestCollection", description = "The latest TEA Collection for this component release", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("latestCollection")
  public TeaCollection getLatestCollection() {
    return latestCollection;
  }

  @JsonProperty("latestCollection")
  public void setLatestCollection(TeaCollection latestCollection) {
    this.latestCollection = latestCollection;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TeaComponentReleaseWithCollection componentReleaseWithCollection = (TeaComponentReleaseWithCollection) o;
    return Objects.equals(this.uuid, componentReleaseWithCollection.uuid) &&
        Objects.equals(this.component, componentReleaseWithCollection.component) &&
        Objects.equals(this.componentName, componentReleaseWithCollection.componentName) &&
        Objects.equals(this.version, componentReleaseWithCollection.version) &&
        Objects.equals(this.createdDate, componentReleaseWithCollection.createdDate) &&
        Objects.equals(this.releaseDate, componentReleaseWithCollection.releaseDate) &&
        Objects.equals(this.preRelease, componentReleaseWithCollection.preRelease) &&
        Objects.equals(this.identifiers, componentReleaseWithCollection.identifiers) &&
        Objects.equals(this.distributions, componentReleaseWithCollection.distributions) &&
        Objects.equals(this.latestCollection, componentReleaseWithCollection.latestCollection);
  }

  @Override
  public int hashCode() {
    return Objects.hash(uuid, component, componentName, version, createdDate, releaseDate, preRelease, identifiers, distributions, latestCollection);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class TeaComponentReleaseWithCollection {\n");
    sb.append("    uuid: ").append(toIndentedString(uuid)).append("\n");
    sb.append("    component: ").append(toIndentedString(component)).append("\n");
    sb.append("    componentName: ").append(toIndentedString(componentName)).append("\n");
    sb.append("    version: ").append(toIndentedString(version)).append("\n");
    sb.append("    createdDate: ").append(toIndentedString(createdDate)).append("\n");
    sb.append("    releaseDate: ").append(toIndentedString(releaseDate)).append("\n");
    sb.append("    preRelease: ").append(toIndentedString(preRelease)).append("\n");
    sb.append("    identifiers: ").append(toIndentedString(identifiers)).append("\n");
    sb.append("    distributions: ").append(toIndentedString(distributions)).append("\n");
    sb.append("    latestCollection: ").append(toIndentedString(latestCollection)).append("\n");
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

