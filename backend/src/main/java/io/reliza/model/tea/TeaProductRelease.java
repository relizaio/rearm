package io.reliza.model.tea;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import io.reliza.model.tea.TeaComponentRef;
import io.reliza.model.tea.TeaIdentifier;
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
 * A specific release of a TEA product
 */

@Schema(name = "productRelease", description = "A specific release of a TEA product")
@JsonTypeName("productRelease")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2025-10-15T13:35:56.249199300-04:00[America/Toronto]", comments = "Generator version: 7.14.0")
public class TeaProductRelease {

  private UUID uuid;

  private @Nullable UUID product;

  private @Nullable String productName;

  private String version;

  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
  private OffsetDateTime createdDate;

  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
  private @Nullable OffsetDateTime releaseDate;

  private @Nullable Boolean preRelease;

  @Valid
  private List<@Valid TeaIdentifier> identifiers = new ArrayList<>();

  @Valid
  private List<@Valid TeaComponentRef> components = new ArrayList<>();

  public TeaProductRelease() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public TeaProductRelease(UUID uuid, String version, OffsetDateTime createdDate, List<@Valid TeaComponentRef> components) {
    this.uuid = uuid;
    this.version = version;
    this.createdDate = createdDate;
    this.components = components;
  }

  public TeaProductRelease uuid(UUID uuid) {
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

  public TeaProductRelease product(@Nullable UUID product) {
    this.product = product;
    return this;
  }

  /**
   * A UUID
   * @return product
   */
  @Valid 
  @Schema(name = "product", description = "A UUID", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("product")
  public @Nullable UUID getProduct() {
    return product;
  }

  public void setProduct(@Nullable UUID product) {
    this.product = product;
  }

  public TeaProductRelease productName(@Nullable String productName) {
    this.productName = productName;
    return this;
  }

  /**
   * Name of the TEA Product this release belongs to
   * @return productName
   */
  
  @Schema(name = "productName", example = "Apache Log4j 2", description = "Name of the TEA Product this release belongs to", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("productName")
  public @Nullable String getProductName() {
    return productName;
  }

  public void setProductName(@Nullable String productName) {
    this.productName = productName;
  }

  public TeaProductRelease version(String version) {
    this.version = version;
    return this;
  }

  /**
   * Version number of the product release
   * @return version
   */
  @NotNull 
  @Schema(name = "version", example = "2.24.3", description = "Version number of the product release", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("version")
  public String getVersion() {
    return version;
  }

  public void setVersion(String version) {
    this.version = version;
  }

  public TeaProductRelease createdDate(OffsetDateTime createdDate) {
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

  public TeaProductRelease releaseDate(@Nullable OffsetDateTime releaseDate) {
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

  public TeaProductRelease preRelease(@Nullable Boolean preRelease) {
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

  public TeaProductRelease identifiers(List<@Valid TeaIdentifier> identifiers) {
    this.identifiers = identifiers;
    return this;
  }

  public TeaProductRelease addIdentifiersItem(TeaIdentifier identifiersItem) {
    if (this.identifiers == null) {
      this.identifiers = new ArrayList<>();
    }
    this.identifiers.add(identifiersItem);
    return this;
  }

  /**
   * List of identifiers for the product release
   * @return identifiers
   */
  @Valid 
  @Schema(name = "identifiers", description = "List of identifiers for the product release", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("identifiers")
  public List<@Valid TeaIdentifier> getIdentifiers() {
    return identifiers;
  }

  public void setIdentifiers(List<@Valid TeaIdentifier> identifiers) {
    this.identifiers = identifiers;
  }

  public TeaProductRelease components(List<@Valid TeaComponentRef> components) {
    this.components = components;
    return this;
  }

  public TeaProductRelease addComponentsItem(TeaComponentRef componentsItem) {
    if (this.components == null) {
      this.components = new ArrayList<>();
    }
    this.components.add(componentsItem);
    return this;
  }

  /**
   * List of component references that compose this product release. A component reference can optionally include the UUID of a specific component release to pin the exact version. 
   * @return components
   */
  @NotNull @Valid 
  @Schema(name = "components", description = "List of component references that compose this product release. A component reference can optionally include the UUID of a specific component release to pin the exact version. ", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("components")
  public List<@Valid TeaComponentRef> getComponents() {
    return components;
  }

  public void setComponents(List<@Valid TeaComponentRef> components) {
    this.components = components;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TeaProductRelease productRelease = (TeaProductRelease) o;
    return Objects.equals(this.uuid, productRelease.uuid) &&
        Objects.equals(this.product, productRelease.product) &&
        Objects.equals(this.productName, productRelease.productName) &&
        Objects.equals(this.version, productRelease.version) &&
        Objects.equals(this.createdDate, productRelease.createdDate) &&
        Objects.equals(this.releaseDate, productRelease.releaseDate) &&
        Objects.equals(this.preRelease, productRelease.preRelease) &&
        Objects.equals(this.identifiers, productRelease.identifiers) &&
        Objects.equals(this.components, productRelease.components);
  }

  @Override
  public int hashCode() {
    return Objects.hash(uuid, product, productName, version, createdDate, releaseDate, preRelease, identifiers, components);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class TeaProductRelease {\n");
    sb.append("    uuid: ").append(toIndentedString(uuid)).append("\n");
    sb.append("    product: ").append(toIndentedString(product)).append("\n");
    sb.append("    productName: ").append(toIndentedString(productName)).append("\n");
    sb.append("    version: ").append(toIndentedString(version)).append("\n");
    sb.append("    createdDate: ").append(toIndentedString(createdDate)).append("\n");
    sb.append("    releaseDate: ").append(toIndentedString(releaseDate)).append("\n");
    sb.append("    preRelease: ").append(toIndentedString(preRelease)).append("\n");
    sb.append("    identifiers: ").append(toIndentedString(identifiers)).append("\n");
    sb.append("    components: ").append(toIndentedString(components)).append("\n");
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

