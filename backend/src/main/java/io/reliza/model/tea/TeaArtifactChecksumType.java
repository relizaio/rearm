/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.tea;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonValue;
import org.openapitools.jackson.nullable.JsonNullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;


import java.util.*;
import jakarta.annotation.Generated;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Checksum algorithm
 */

@Generated(value = "io.reliza.codegen.languages.SpringCodegen", date = "2025-05-08T09:03:56.085827200-04:00[America/Toronto]", comments = "Generator version: 7.13.0")
public enum TeaArtifactChecksumType {
  
  MD5("MD5"),
  
  SHA_1("SHA-1"),
  
  SHA_256("SHA-256"),
  
  SHA_384("SHA-384"),
  
  SHA_512("SHA-512"),
  
  SHA3_256("SHA3-256"),
  
  SHA3_384("SHA3-384"),
  
  SHA3_512("SHA3-512"),
  
  BLAKE2B_256("BLAKE2b-256"),
  
  BLAKE2B_384("BLAKE2b-384"),
  
  BLAKE2B_512("BLAKE2b-512"),
  
  BLAKE3("BLAKE3");

  private final String value;

  TeaArtifactChecksumType(String value) {
    this.value = value;
  }

  @JsonValue
  public String getValue() {
    return value;
  }

  @Override
  public String toString() {
    return String.valueOf(value);
  }

  @JsonCreator
  public static TeaArtifactChecksumType fromValue(String value) {
    for (TeaArtifactChecksumType b : TeaArtifactChecksumType.values()) {
      if (b.value.equals(value)) {
        return b;
      }
    }
    throw new IllegalArgumentException("Unexpected value '" + value + "'");
  }
}

