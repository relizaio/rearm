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
 * Well-known compliance document types. When idType is COMPLIANCE_DOCUMENT, the idValue shall be one of these values. 
 */

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2026-10-08T02:50:49.497696677Z[Etc/UTC]", comments = "Generator version: 7.21.0")
public enum TeaComplianceDocumentType {
  
  SOC_2_TYPE_I("SOC_2_TYPE_I"),
  
  SOC_2_TYPE_II("SOC_2_TYPE_II"),
  
  SOC_3("SOC_3"),
  
  ISO_27001("ISO_27001"),
  
  ISO_27017("ISO_27017"),
  
  ISO_27018("ISO_27018"),
  
  ISO_27701("ISO_27701"),
  
  ISO_42001("ISO_42001"),
  
  PCI_DSS("PCI_DSS"),
  
  HIPAA("HIPAA"),
  
  FEDRAMP("FEDRAMP"),
  
  GDPR("GDPR"),
  
  CSA_STAR("CSA_STAR"),
  
  NIST_800_53("NIST_800_53"),
  
  NIST_800_171("NIST_800_171"),
  
  CMMC("CMMC"),
  
  HITRUST("HITRUST"),
  
  TISAX("TISAX"),
  
  CYBER_ESSENTIALS("CYBER_ESSENTIALS"),
  
  CYBER_ESSENTIALS_PLUS("CYBER_ESSENTIALS_PLUS"),
  
  EU_DECLARATION_OF_CONFORMITY("EU_DECLARATION_OF_CONFORMITY");

  private final String value;

  TeaComplianceDocumentType(String value) {
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
  public static TeaComplianceDocumentType fromValue(String value) {
    for (TeaComplianceDocumentType b : TeaComplianceDocumentType.values()) {
      if (b.value.equals(value)) {
        return b;
      }
    }
    throw new IllegalArgumentException("Unexpected value '" + value + "'");
  }
}

