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
 * Type of TEA collection update.  `VEX_UPDATED` covers adding or revising an artifact of type `VULNERABILITIES`, including a revision whose external `url` or `signatureUrl` changed. VEX and VDR share that type. `ARTIFACT_UPDATED` covers a new revision of an artifact whose type is not `VULNERABILITIES`, including a revision whose external `url` or `signatureUrl` changed. Replacing an embedded artifact revision solely because its published `url` or `signatureUrl` changed is `VEX_UPDATED` when the artifact type is `VULNERABILITIES`, and `ARTIFACT_UPDATED` otherwise. Removing an artifact, including one of type `VULNERABILITIES`, counts as `ARTIFACT_REMOVED`.  The first Collection version shall use `INITIAL_RELEASE`. In subsequent versions, adding or revising an artifact of type `VULNERABILITIES` counts as `VEX_UPDATED`. When multiple change kinds occur, the server shall select `updateReason.type` using the precedence rule below.  When a collection version other than the first contains changes of more than one kind, the server shall set `updateReason.type` to the first applicable value in this order: `VEX_UPDATED`, `ARTIFACT_REMOVED`, `ARTIFACT_ADDED`, `ARTIFACT_UPDATED`. `comment` can describe the other changes. A client that needs the complete set of changes should compare the version with the previous one. 
 */

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2026-10-08T02:50:49.497696677Z[Etc/UTC]", comments = "Generator version: 7.21.0")
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

