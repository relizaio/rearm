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
 * Classification of TEA error response
 */

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2025-10-11T15:33:29.932635600-04:00[America/Toronto]", comments = "Generator version: 7.14.0")
public enum TeaUnknownErrorType {
  
  OBJECT_UNKNOWN("OBJECT_UNKNOWN"),
  
  OBJECT_NOT_SHAREABLE("OBJECT_NOT_SHAREABLE");

  private final String value;

  TeaUnknownErrorType(String value) {
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
  public static TeaUnknownErrorType fromValue(String value) {
    for (TeaUnknownErrorType b : TeaUnknownErrorType.values()) {
      if (b.value.equals(value)) {
        return b;
      }
    }
    throw new IllegalArgumentException("Unexpected value '" + value + "'");
  }
}

