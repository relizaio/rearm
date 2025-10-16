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
 * Specifies the type of external reference.
 */

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2025-10-15T13:35:56.249199300-04:00[America/Toronto]", comments = "Generator version: 7.14.0")
public enum TeaArtifactType {
  
  ATTESTATION("ATTESTATION"),
  
  BOM("BOM"),
  
  BUILD_META("BUILD_META"),
  
  CERTIFICATION("CERTIFICATION"),
  
  FORMULATION("FORMULATION"),
  
  LICENSE("LICENSE"),
  
  RELEASE_NOTES("RELEASE_NOTES"),
  
  SECURITY_TXT("SECURITY_TXT"),
  
  THREAT_MODEL("THREAT_MODEL"),
  
  VULNERABILITIES("VULNERABILITIES"),
  
  OTHER("OTHER");

  private final String value;

  TeaArtifactType(String value) {
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
  public static TeaArtifactType fromValue(String value) {
    for (TeaArtifactType b : TeaArtifactType.values()) {
      if (b.value.equals(value)) {
        return b;
      }
    }
    throw new IllegalArgumentException("Unexpected value '" + value + "'");
  }
}

