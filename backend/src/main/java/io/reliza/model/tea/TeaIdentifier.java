package io.reliza.model.tea;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.annotation.JsonValue;
import io.reliza.model.tea.TeaIdentifierType;
import org.springframework.lang.Nullable;
import org.openapitools.jackson.nullable.JsonNullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * An identifier of a TEA object with a specified type. &#x60;COMPLIANCE_DOCUMENT&#x60; is valid only on TEA Components and TEA Component Releases (see &#x60;identifier-type&#x60;). 
 */

@Schema(name = "identifier", description = "An identifier of a TEA object with a specified type. `COMPLIANCE_DOCUMENT` is valid only on TEA Components and TEA Component Releases (see `identifier-type`). ")
@JsonTypeName("identifier")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2026-10-08T02:50:49.497696677Z[Etc/UTC]", comments = "Generator version: 7.21.0")
public class TeaIdentifier {

  private TeaIdentifierType idType;

  private String idValue;

  public TeaIdentifier() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public TeaIdentifier(TeaIdentifierType idType, String idValue) {
    this.idType = idType;
    this.idValue = idValue;
  }

  public TeaIdentifier idType(TeaIdentifierType idType) {
    this.idType = idType;
    return this;
  }

  /**
   * Type of identifier, e.g. `TEI`, `PURL`, `CPE`
   * @return idType
   */
  @NotNull @Valid 
  @Schema(name = "idType", description = "Type of identifier, e.g. `TEI`, `PURL`, `CPE`", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("idType")
  public TeaIdentifierType getIdType() {
    return idType;
  }

  @JsonProperty("idType")
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
  @NotNull 
  @Schema(name = "idValue", description = "Identifier value", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("idValue")
  public String getIdValue() {
    return idValue;
  }

  @JsonProperty("idValue")
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
    sb.append("class TeaIdentifier {\n");
    sb.append("    idType: ").append(toIndentedString(idType)).append("\n");
    sb.append("    idValue: ").append(toIndentedString(idValue)).append("\n");
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

