/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.tea;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import io.reliza.model.tea.TeaIdentifier;
import org.springframework.lang.Nullable;
import org.openapitools.jackson.nullable.JsonNullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * A TEA product
 */

@Schema(name = "product", description = "A TEA product")
@JsonTypeName("product")
@Generated(value = "io.reliza.codegen.languages.SpringCodegen", date = "2025-05-08T09:03:56.085827200-04:00[America/Toronto]", comments = "Generator version: 7.13.0")
public class TeaProduct {

  private UUID uuid;

  private String name;

  @Valid
  private List<@Valid TeaIdentifier> identifiers = new ArrayList<>();

  @Valid
  private List<UUID> components = new ArrayList<>();

  public TeaProduct() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public TeaProduct(UUID uuid, String name, List<@Valid TeaIdentifier> identifiers, List<UUID> components) {
    this.uuid = uuid;
    this.name = name;
    this.identifiers = identifiers;
    this.components = components;
  }

  public TeaProduct uuid(UUID uuid) {
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

  public TeaProduct name(String name) {
    this.name = name;
    return this;
  }

  /**
   * Product name
   * @return name
   */
  @NotNull 
  @Schema(name = "name", description = "Product name", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("name")
  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public TeaProduct identifiers(List<@Valid TeaIdentifier> identifiers) {
    this.identifiers = identifiers;
    return this;
  }

  public TeaProduct addIdentifiersItem(TeaIdentifier identifiersItem) {
    if (this.identifiers == null) {
      this.identifiers = new ArrayList<>();
    }
    this.identifiers.add(identifiersItem);
    return this;
  }

  /**
   * List of identifiers for the product
   * @return identifiers
   */
  @NotNull @Valid 
  @Schema(name = "identifiers", description = "List of identifiers for the product", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("identifiers")
  public List<@Valid TeaIdentifier> getIdentifiers() {
    return identifiers;
  }

  public void setIdentifiers(List<@Valid TeaIdentifier> identifiers) {
    this.identifiers = identifiers;
  }

  public TeaProduct components(List<UUID> components) {
    this.components = components;
    return this;
  }

  public TeaProduct addComponentsItem(UUID componentsItem) {
    if (this.components == null) {
      this.components = new ArrayList<>();
    }
    this.components.add(componentsItem);
    return this;
  }

  /**
   * List of TEA components for the product
   * @return components
   */
  @NotNull @Valid 
  @Schema(name = "components", description = "List of TEA components for the product", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("components")
  public List<UUID> getComponents() {
    return components;
  }

  public void setComponents(List<UUID> components) {
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
    TeaProduct product = (TeaProduct) o;
    return Objects.equals(this.uuid, product.uuid) &&
        Objects.equals(this.name, product.name) &&
        Objects.equals(this.identifiers, product.identifiers) &&
        Objects.equals(this.components, product.components);
  }

  @Override
  public int hashCode() {
    return Objects.hash(uuid, name, identifiers, components);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class Product {\n");
    sb.append("    uuid: ").append(toIndentedString(uuid)).append("\n");
    sb.append("    name: ").append(toIndentedString(name)).append("\n");
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

