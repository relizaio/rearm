package io.reliza.model.tea;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import io.reliza.model.tea.TeaCleSupportDefinition;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.lang.Nullable;
import org.openapitools.jackson.nullable.JsonNullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * Container for reusable CLE policy definitions
 */

@Schema(name = "cle-definitions", description = "Container for reusable CLE policy definitions")
@JsonTypeName("cle-definitions")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2026-03-29T10:44:15.267909500-04:00[America/Toronto]", comments = "Generator version: 7.21.0")
public class TeaCleDefinitions {

  @Valid
  private List<@Valid TeaCleSupportDefinition> support = new ArrayList<>();

  public TeaCleDefinitions support(List<@Valid TeaCleSupportDefinition> support) {
    this.support = support;
    return this;
  }

  public TeaCleDefinitions addSupportItem(TeaCleSupportDefinition supportItem) {
    if (this.support == null) {
      this.support = new ArrayList<>();
    }
    this.support.add(supportItem);
    return this;
  }

  /**
   * List of support policies
   * @return support
   */
  @Valid 
  @Schema(name = "support", description = "List of support policies", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("support")
  public List<@Valid TeaCleSupportDefinition> getSupport() {
    return support;
  }

  @JsonProperty("support")
  public void setSupport(List<@Valid TeaCleSupportDefinition> support) {
    this.support = support;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TeaCleDefinitions cleDefinitions = (TeaCleDefinitions) o;
    return Objects.equals(this.support, cleDefinitions.support);
  }

  @Override
  public int hashCode() {
    return Objects.hash(support);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class TeaCleDefinitions {\n");
    sb.append("    support: ").append(toIndentedString(support)).append("\n");
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

