package io.reliza.model.tea;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import io.reliza.model.tea.TeaCollection;
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
 * A TEA Product Release together with its latest collection.  Extends &#x60;product-release&#x60; with a &#x60;latestCollection&#x60; property; every field of &#x60;product-release&#x60; applies unchanged. 
 */

@Schema(name = "product-release-with-collection", description = "A TEA Product Release together with its latest collection.  Extends `product-release` with a `latestCollection` property; every field of `product-release` applies unchanged. ")
@JsonTypeName("product-release-with-collection")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2026-10-08T02:50:49.497696677Z[Etc/UTC]", comments = "Generator version: 7.21.0")
public class TeaProductReleaseWithCollection {

  private UUID uuid;

  private UUID product;

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

  private TeaCollection latestCollection;

  public TeaProductReleaseWithCollection() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public TeaProductReleaseWithCollection(UUID uuid, UUID product, String version, OffsetDateTime createdDate, List<@Valid TeaComponentRef> components, TeaCollection latestCollection) {
    this.uuid = uuid;
    this.product = product;
    this.version = version;
    this.createdDate = createdDate;
    this.components = components;
    this.latestCollection = latestCollection;
  }

  public TeaProductReleaseWithCollection uuid(UUID uuid) {
    this.uuid = uuid;
    return this;
  }

  /**
   * A unique identifier for the TEA Product Release. Within an API base it shall not also identify a Component Release; see `doc/tea-uuid-scope.md`. 
   * @return uuid
   */
  @NotNull @Valid 
  @Schema(name = "uuid", description = "A unique identifier for the TEA Product Release. Within an API base it shall not also identify a Component Release; see `doc/tea-uuid-scope.md`. ", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("uuid")
  public UUID getUuid() {
    return uuid;
  }

  @JsonProperty("uuid")
  public void setUuid(UUID uuid) {
    this.uuid = uuid;
  }

  public TeaProductReleaseWithCollection product(UUID product) {
    this.product = product;
    return this;
  }

  /**
   * UUID of the TEA Product this release belongs to
   * @return product
   */
  @NotNull @Valid 
  @Schema(name = "product", description = "UUID of the TEA Product this release belongs to", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("product")
  public UUID getProduct() {
    return product;
  }

  @JsonProperty("product")
  public void setProduct(UUID product) {
    this.product = product;
  }

  public TeaProductReleaseWithCollection productName(@Nullable String productName) {
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

  @JsonProperty("productName")
  public void setProductName(@Nullable String productName) {
    this.productName = productName;
  }

  public TeaProductReleaseWithCollection version(String version) {
    this.version = version;
    return this;
  }

  /**
   * Human-readable version string of the product release
   * @return version
   */
  @NotNull 
  @Schema(name = "version", example = "2.24.3", description = "Human-readable version string of the product release", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("version")
  public String getVersion() {
    return version;
  }

  @JsonProperty("version")
  public void setVersion(String version) {
    this.version = version;
  }

  public TeaProductReleaseWithCollection createdDate(OffsetDateTime createdDate) {
    this.createdDate = createdDate;
    return this;
  }

  /**
   * Timestamp when this Product Release was created in TEA (for sorting purposes)
   * @return createdDate
   */
  @NotNull @Valid @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$") 
  @Schema(name = "createdDate", example = "2024-03-20T15:30:00Z", description = "Timestamp when this Product Release was created in TEA (for sorting purposes)", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("createdDate")
  public OffsetDateTime getCreatedDate() {
    return createdDate;
  }

  @JsonProperty("createdDate")
  public void setCreatedDate(OffsetDateTime createdDate) {
    this.createdDate = createdDate;
  }

  public TeaProductReleaseWithCollection releaseDate(@Nullable OffsetDateTime releaseDate) {
    this.releaseDate = releaseDate;
    return this;
  }

  /**
   * Timestamp of the product release
   * @return releaseDate
   */
  @Valid @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$") 
  @Schema(name = "releaseDate", example = "2024-03-20T15:30:00Z", description = "Timestamp of the product release", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("releaseDate")
  public @Nullable OffsetDateTime getReleaseDate() {
    return releaseDate;
  }

  @JsonProperty("releaseDate")
  public void setReleaseDate(@Nullable OffsetDateTime releaseDate) {
    this.releaseDate = releaseDate;
  }

  public TeaProductReleaseWithCollection preRelease(@Nullable Boolean preRelease) {
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

  public TeaProductReleaseWithCollection identifiers(List<@Valid TeaIdentifier> identifiers) {
    this.identifiers = identifiers;
    return this;
  }

  public TeaProductReleaseWithCollection addIdentifiersItem(TeaIdentifier identifiersItem) {
    if (this.identifiers == null) {
      this.identifiers = new ArrayList<>();
    }
    this.identifiers.add(identifiersItem);
    return this;
  }

  /**
   * Array of identifiers for the product release (`idType` is `CPE`, `TEI` or `PURL`; `COMPLIANCE_DOCUMENT` is not valid here) 
   * @return identifiers
   */
  @Valid 
  @Schema(name = "identifiers", description = "Array of identifiers for the product release (`idType` is `CPE`, `TEI` or `PURL`; `COMPLIANCE_DOCUMENT` is not valid here) ", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("identifiers")
  public List<@Valid TeaIdentifier> getIdentifiers() {
    return identifiers;
  }

  @JsonProperty("identifiers")
  public void setIdentifiers(List<@Valid TeaIdentifier> identifiers) {
    this.identifiers = identifiers;
  }

  public TeaProductReleaseWithCollection components(List<@Valid TeaComponentRef> components) {
    this.components = components;
    return this;
  }

  public TeaProductReleaseWithCollection addComponentsItem(TeaComponentRef componentsItem) {
    if (this.components == null) {
      this.components = new ArrayList<>();
    }
    this.components.add(componentsItem);
    return this;
  }

  /**
   * Array of component references that compose this product release. A component reference can optionally include the UUID of a specific component release to pin the exact version. 
   * @return components
   */
  @NotNull @Valid 
  @Schema(name = "components", description = "Array of component references that compose this product release. A component reference can optionally include the UUID of a specific component release to pin the exact version. ", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("components")
  public List<@Valid TeaComponentRef> getComponents() {
    return components;
  }

  @JsonProperty("components")
  public void setComponents(List<@Valid TeaComponentRef> components) {
    this.components = components;
  }

  public TeaProductReleaseWithCollection latestCollection(TeaCollection latestCollection) {
    this.latestCollection = latestCollection;
    return this;
  }

  /**
   * The latest TEA Collection for this product release
   * @return latestCollection
   */
  @NotNull @Valid 
  @Schema(name = "latestCollection", description = "The latest TEA Collection for this product release", requiredMode = Schema.RequiredMode.REQUIRED)
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
    TeaProductReleaseWithCollection productReleaseWithCollection = (TeaProductReleaseWithCollection) o;
    return Objects.equals(this.uuid, productReleaseWithCollection.uuid) &&
        Objects.equals(this.product, productReleaseWithCollection.product) &&
        Objects.equals(this.productName, productReleaseWithCollection.productName) &&
        Objects.equals(this.version, productReleaseWithCollection.version) &&
        Objects.equals(this.createdDate, productReleaseWithCollection.createdDate) &&
        Objects.equals(this.releaseDate, productReleaseWithCollection.releaseDate) &&
        Objects.equals(this.preRelease, productReleaseWithCollection.preRelease) &&
        Objects.equals(this.identifiers, productReleaseWithCollection.identifiers) &&
        Objects.equals(this.components, productReleaseWithCollection.components) &&
        Objects.equals(this.latestCollection, productReleaseWithCollection.latestCollection);
  }

  @Override
  public int hashCode() {
    return Objects.hash(uuid, product, productName, version, createdDate, releaseDate, preRelease, identifiers, components, latestCollection);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class TeaProductReleaseWithCollection {\n");
    sb.append("    uuid: ").append(toIndentedString(uuid)).append("\n");
    sb.append("    product: ").append(toIndentedString(product)).append("\n");
    sb.append("    productName: ").append(toIndentedString(productName)).append("\n");
    sb.append("    version: ").append(toIndentedString(version)).append("\n");
    sb.append("    createdDate: ").append(toIndentedString(createdDate)).append("\n");
    sb.append("    releaseDate: ").append(toIndentedString(releaseDate)).append("\n");
    sb.append("    preRelease: ").append(toIndentedString(preRelease)).append("\n");
    sb.append("    identifiers: ").append(toIndentedString(identifiers)).append("\n");
    sb.append("    components: ").append(toIndentedString(components)).append("\n");
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

