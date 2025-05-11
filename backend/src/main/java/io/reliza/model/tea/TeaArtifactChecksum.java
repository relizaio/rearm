/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.tea;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.annotation.JsonValue;
import io.reliza.model.tea.TeaArtifactChecksumType;
import org.springframework.lang.Nullable;
import org.openapitools.jackson.nullable.JsonNullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * ArtifactChecksum
 */

@JsonTypeName("artifact-checksum")
@Generated(value = "io.reliza.codegen.languages.SpringCodegen", date = "2025-05-08T09:03:56.085827200-04:00[America/Toronto]", comments = "Generator version: 7.13.0")
public class TeaArtifactChecksum {

  private @Nullable TeaArtifactChecksumType algType;

  private @Nullable String algValue;

  public TeaArtifactChecksum algType(TeaArtifactChecksumType algType) {
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
  public TeaArtifactChecksumType getAlgType() {
    return algType;
  }

  public void setAlgType(TeaArtifactChecksumType algType) {
    this.algType = algType;
  }

  public TeaArtifactChecksum algValue(String algValue) {
    this.algValue = algValue;
    return this;
  }

  /**
   * Checksum value
   * @return algValue
   */
  
  @Schema(name = "algValue", description = "Checksum value", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("algValue")
  public String getAlgValue() {
    return algValue;
  }

  public void setAlgValue(String algValue) {
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
    TeaArtifactChecksum artifactChecksum = (TeaArtifactChecksum) o;
    return Objects.equals(this.algType, artifactChecksum.algType) &&
        Objects.equals(this.algValue, artifactChecksum.algValue);
  }

  @Override
  public int hashCode() {
    return Objects.hash(algType, algValue);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class ArtifactChecksum {\n");
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

