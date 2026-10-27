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
 * Enumeration of identifier types.  `COMPLIANCE_DOCUMENT` marks a TEA Component or TEA Component Release as carrying a compliance document (for example a SOC 2 report), so that it can be discovered with `idType=COMPLIANCE_DOCUMENT` on `/components` and `/componentReleases`; the document itself is published as a TEA Artifact of the component release. It shall not be used on products, product releases, distributions, or CLE events. 
 */

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2026-10-08T02:50:49.497696677Z[Etc/UTC]", comments = "Generator version: 7.21.0")
public enum TeaIdentifierType {
  
  CPE("CPE"),
  
  TEI("TEI"),
  
  PURL("PURL"),
  
  COMPLIANCE_DOCUMENT("COMPLIANCE_DOCUMENT");

  private final String value;

  TeaIdentifierType(String value) {
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
  public static TeaIdentifierType fromValue(String value) {
    for (TeaIdentifierType b : TeaIdentifierType.values()) {
      if (b.value.equals(value)) {
        return b;
      }
    }
    throw new IllegalArgumentException("Unexpected value '" + value + "'");
  }
}

