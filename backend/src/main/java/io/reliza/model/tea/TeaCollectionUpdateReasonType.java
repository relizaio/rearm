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
 * Type of TEA collection update
 */

@Generated(value = "io.reliza.codegen.languages.SpringCodegen", date = "2025-05-08T09:03:56.085827200-04:00[America/Toronto]", comments = "Generator version: 7.13.0")
public enum TeaCollectionUpdateReasonType {
  
  INITIAL_RELEASE("INITIAL_RELEASE"),
  
  VEX_UPDATED("VEX_UPDATED"),
  
  ARTIFACT_UPDATED("ARTIFACT_UPDATED"),
  
  ARTIFACT_ADDED("ARTIFACT_ADDED"),
  
  ARTIFACT_REMOVED("ARTIFACT_REMOVED");

  private final String value;

  TeaCollectionUpdateReasonType(String value) {
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
  public static TeaCollectionUpdateReasonType fromValue(String value) {
    for (TeaCollectionUpdateReasonType b : TeaCollectionUpdateReasonType.values()) {
      if (b.value.equals(value)) {
        return b;
      }
    }
    throw new IllegalArgumentException("Unexpected value '" + value + "'");
  }
}

