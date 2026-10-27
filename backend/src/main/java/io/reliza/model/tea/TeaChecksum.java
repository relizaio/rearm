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
 * Integrity digest for identified content bytes. &#x60;algValue&#x60; is the lowercase hexadecimal encoding of the digest produced by &#x60;algType&#x60; over those bytes. The hex length shall match the algorithm&#39;s digest size (for example 40 for SHA-1, 64 for SHA-256 / SHA3-256 / BLAKE2b-256 / BLAKE3, 96 for SHA-384 / SHA3-384 / BLAKE2b-384, 128 for SHA-512 / SHA3-512 / BLAKE2b-512). 
 */

@Schema(name = "checksum", description = "Integrity digest for identified content bytes. `algValue` is the lowercase hexadecimal encoding of the digest produced by `algType` over those bytes. The hex length shall match the algorithm's digest size (for example 40 for SHA-1, 64 for SHA-256 / SHA3-256 / BLAKE2b-256 / BLAKE3, 96 for SHA-384 / SHA3-384 / BLAKE2b-384, 128 for SHA-512 / SHA3-512 / BLAKE2b-512). ")
@JsonTypeName("checksum")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2026-10-08T02:50:49.497696677Z[Etc/UTC]", comments = "Generator version: 7.21.0")
public class TeaChecksum {

  private TeaChecksumType algType;

  private String algValue;

  public TeaChecksum() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public TeaChecksum(TeaChecksumType algType, String algValue) {
    this.algType = algType;
    this.algValue = algValue;
  }

  public TeaChecksum algType(TeaChecksumType algType) {
    this.algType = algType;
    return this;
  }

  /**
   * Checksum algorithm
   * @return algType
   */
  @NotNull @Valid 
  @Schema(name = "algType", description = "Checksum algorithm", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("algType")
  public TeaChecksumType getAlgType() {
    return algType;
  }

  @JsonProperty("algType")
  public void setAlgType(TeaChecksumType algType) {
    this.algType = algType;
  }

  public TeaChecksum algValue(String algValue) {
    this.algValue = algValue;
    return this;
  }

  /**
   * Lowercase hexadecimal digest value for `algType`. Do not use Base64 or uppercase hex. 
   * @return algValue
   */
  @NotNull @Pattern(regexp = "^[0-9a-f]+$") @Size(min = 40, max = 128) 
  @Schema(name = "algValue", example = "9da736a1cdd27231e70187cbc67398d29ca0b714f885e7032da9f1fb247693c1", description = "Lowercase hexadecimal digest value for `algType`. Do not use Base64 or uppercase hex. ", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("algValue")
  public String getAlgValue() {
    return algValue;
  }

  @JsonProperty("algValue")
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
  private String toIndentedString(@Nullable Object o) {
    return o == null ? "null" : o.toString().replace("\n", "\n    ");
  }
}

