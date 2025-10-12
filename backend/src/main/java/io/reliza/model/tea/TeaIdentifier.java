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
 * An identifier with a specified type
 */

@Schema(name = "identifier", description = "An identifier with a specified type")
@JsonTypeName("identifier")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2025-10-11T15:33:29.932635600-04:00[America/Toronto]", comments = "Generator version: 7.14.0")
public class TeaIdentifier {

  private @Nullable TeaIdentifierType idType;

  private @Nullable String idValue;

  public TeaIdentifier idType(@Nullable TeaIdentifierType idType) {
    this.idType = idType;
    return this;
  }

  /**
   * Type of identifier, e.g. `TEI`, `PURL`, `CPE`
   * @return idType
   */
  @Valid 
  @Schema(name = "idType", description = "Type of identifier, e.g. `TEI`, `PURL`, `CPE`", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("idType")
  public @Nullable TeaIdentifierType getIdType() {
    return idType;
  }

  public void setIdType(@Nullable TeaIdentifierType idType) {
    this.idType = idType;
  }

  public TeaIdentifier idValue(@Nullable String idValue) {
    this.idValue = idValue;
    return this;
  }

  /**
   * Identifier value
   * @return idValue
   */
  
  @Schema(name = "idValue", description = "Identifier value", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("idValue")
  public @Nullable String getIdValue() {
    return idValue;
  }

  public void setIdValue(@Nullable String idValue) {
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
  private String toIndentedString(Object o) {
    if (o == null) {
      return "null";
    }
    return o.toString().replace("\n", "\n    ");
  }
}

