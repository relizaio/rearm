package io.reliza.model.tea;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import io.reliza.model.tea.TeaIdentifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
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
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2025-10-15T13:35:56.249199300-04:00[America/Toronto]", comments = "Generator version: 7.14.0")
public class TeaProduct {

  private UUID uuid;

  private String name;

  @Valid
  private List<@Valid TeaIdentifier> identifiers = new ArrayList<>();

  public TeaProduct() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public TeaProduct(UUID uuid, String name, List<@Valid TeaIdentifier> identifiers) {
    this.uuid = uuid;
    this.name = name;
    this.identifiers = identifiers;
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
   * List of identifiers for the product, like TEI, CPE, PURL or other identifiers 
   * @return identifiers
   */
  @NotNull @Valid 
  @Schema(name = "identifiers", description = "List of identifiers for the product, like TEI, CPE, PURL or other identifiers ", requiredMode = Schema.RequiredMode.REQUIRED)
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
    TeaProduct product = (TeaProduct) o;
    return Objects.equals(this.uuid, product.uuid) &&
        Objects.equals(this.name, product.name) &&
        Objects.equals(this.identifiers, product.identifiers);
  }

  @Override
  public int hashCode() {
    return Objects.hash(uuid, name, identifiers);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class TeaProduct {\n");
    sb.append("    uuid: ").append(toIndentedString(uuid)).append("\n");
    sb.append("    name: ").append(toIndentedString(name)).append("\n");
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

