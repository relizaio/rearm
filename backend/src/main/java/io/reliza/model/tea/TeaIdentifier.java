/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.tea;

import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import org.springframework.lang.Nullable;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.annotation.Generated;

/**
 * An identifier with a specified type
 */

@Schema(name = "identifier", description = "An identifier with a specified type")
@JsonTypeName("identifier")
@Generated(value = "io.reliza.codegen.languages.SpringCodegen", date = "2025-05-08T09:03:56.085827200-04:00[America/Toronto]", comments = "Generator version: 7.13.0")
public class TeaIdentifier {

  private @Nullable TeaIdentifierType idType;

  private @Nullable String idValue;

  public TeaIdentifier idType(TeaIdentifierType idType) {
    this.idType = idType;
    return this;
  }

  /**
   * Type of identifier, e.g. `tei`, `purl`, `cpe`
   * @return idType
   */
  @Valid 
  @Schema(name = "idType", description = "Type of identifier, e.g. `tei`, `purl`, `cpe`", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("idType")
  public TeaIdentifierType getIdType() {
    return idType;
  }

  public void setIdType(TeaIdentifierType idType) {
    this.idType = idType;
  }

  public TeaIdentifier idValue(String idValue) {
    this.idValue = idValue;
    return this;
  }

  /**
   * Identifier value
   * @return idValue
   */
  
  @Schema(name = "idValue", description = "Identifier value", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("idValue")
  public String getIdValue() {
    return idValue;
  }

  public void setIdValue(String idValue) {
    this.idValue = idValue;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TeaIdentifier identifier = (TeaIdentifier) o;
    return Objects.equals(this.idType, identifier.idType) &&
        Objects.equals(this.idValue, identifier.idValue);
  }

  @Override
  public int hashCode() {
    return Objects.hash(idType, idValue);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class Identifier {\n");
    sb.append("    idType: ").append(toIndentedString(idType)).append("\n");
    sb.append("    idValue: ").append(toIndentedString(idValue)).append("\n");
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

