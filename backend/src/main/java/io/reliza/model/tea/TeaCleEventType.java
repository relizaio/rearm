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
 * The type of CLE lifecycle event
 */

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2026-03-29T10:44:15.267909500-04:00[America/Toronto]", comments = "Generator version: 7.21.0")
public enum TeaCleEventType {
  
  RELEASED("released"),
  
  END_OF_DEVELOPMENT("endOfDevelopment"),
  
  END_OF_SUPPORT("endOfSupport"),
  
  END_OF_LIFE("endOfLife"),
  
  END_OF_DISTRIBUTION("endOfDistribution"),
  
  END_OF_MARKETING("endOfMarketing"),
  
  SUPERSEDED_BY("supersededBy"),
  
  COMPONENT_RENAMED("componentRenamed"),
  
  WITHDRAWN("withdrawn");

  private final String value;

  TeaCleEventType(String value) {
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
  public static TeaCleEventType fromValue(String value) {
    for (TeaCleEventType b : TeaCleEventType.values()) {
      if (b.value.equals(value)) {
        return b;
      }
    }
    throw new IllegalArgumentException("Unexpected value '" + value + "'");
  }
}

