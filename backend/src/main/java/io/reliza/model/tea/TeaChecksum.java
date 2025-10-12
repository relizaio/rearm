package io.reliza.model.tea;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.annotation.JsonValue;
import io.reliza.model.tea.TeaChecksumType;
import org.springframework.lang.Nullable;
import org.openapitools.jackson.nullable.JsonNullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * TeaChecksum
 */

@JsonTypeName("checksum")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2025-10-11T15:33:29.932635600-04:00[America/Toronto]", comments = "Generator version: 7.14.0")
public class TeaChecksum {

  private @Nullable TeaChecksumType algType;

  private @Nullable String algValue;

  public TeaChecksum algType(@Nullable TeaChecksumType algType) {
    this.algType = algType;
    return this;
  }

  /**
   * Checksum algorithm
   * @return algType
   */
  @Valid 
  @Schema(name = "algType", description = "Checksum algorithm", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("algType")
  public @Nullable TeaChecksumType getAlgType() {
    return algType;
  }

  public void setAlgType(@Nullable TeaChecksumType algType) {
    this.algType = algType;
  }

  public TeaChecksum algValue(@Nullable String algValue) {
    this.algValue = algValue;
    return this;
  }

  /**
   * Checksum value
   * @return algValue
   */
  
  @Schema(name = "algValue", description = "Checksum value", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("algValue")
  public @Nullable String getAlgValue() {
    return algValue;
  }

  public void setAlgValue(@Nullable String algValue) {
    this.algValue = algValue;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TeaChecksum checksum = (TeaChecksum) o;
    return Objects.equals(this.algType, checksum.algType) &&
        Objects.equals(this.algValue, checksum.algValue);
  }

  @Override
  public int hashCode() {
    return Objects.hash(algType, algValue);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class TeaChecksum {\n");
    sb.append("    algType: ").append(toIndentedString(algType)).append("\n");
    sb.append("    algValue: ").append(toIndentedString(algValue)).append("\n");
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

