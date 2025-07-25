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
  
  SHA1("SHA1"),
  
  SHA_256("SHA_256"),
  
  SHA_384("SHA_384"),
  
  SHA_512("SHA_512"),
  
  SHA3_256("SHA3_256"),
  
  SHA3_384("SHA3_384"),
  
  SHA3_512("SHA3_512"),
  
  BLAKE2B_256("BLAKE2b_256"),
  
  BLAKE2B_384("BLAKE2b_384"),
  
  BLAKE2B_512("BLAKE2b_512"),
  
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
  
  /**
   * Parses a digest type string (e.g., "SHA256", "sha256") to the corresponding enum value.
   * Handles common variations and case-insensitive matching.
   * 
   * @param digestTypeString the digest type string to parse
   * @return the corresponding TeaArtifactChecksumType, or null if not supported
   */
  public static TeaArtifactChecksumType parseDigestType(String digestTypeString) {
    if (digestTypeString == null || digestTypeString.trim().isEmpty()) {
      return null;
    }
    
    String upperCaseType = digestTypeString.toUpperCase().trim();
    
    switch (upperCaseType) {
      case "MD5":
        return MD5;
      case "SHA1":
        return SHA1;
      case "SHA256":
        return SHA_256;
      case "SHA384":
        return SHA_384;
      case "SHA512":
        return SHA_512;
      case "SHA3256":
      case "SHA3_256":
        return SHA3_256;
      case "SHA3384":
      case "SHA3_384":
        return SHA3_384;
      case "SHA3512":
      case "SHA3_512":
        return SHA3_512;
      case "BLAKE2B256":
      case "BLAKE2B_256":
        return BLAKE2B_256;
      case "BLAKE2B384":
      case "BLAKE2B_384":
        return BLAKE2B_384;
      case "BLAKE2B512":
      case "BLAKE2B_512":
        return BLAKE2B_512;
      case "BLAKE3":
        return BLAKE3;
      default:
        return null; // Unsupported digest type
    }
  }
}

