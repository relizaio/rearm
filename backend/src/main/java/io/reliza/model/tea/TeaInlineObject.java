/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.tea;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import io.reliza.model.tea.TeaProduct;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.lang.Nullable;
import org.openapitools.jackson.nullable.JsonNullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * InlineObject
 */

@JsonTypeName("inline_object")
@Generated(value = "io.reliza.codegen.languages.SpringCodegen", date = "2025-05-08T09:03:56.085827200-04:00[America/Toronto]", comments = "Generator version: 7.13.0")
public class TeaInlineObject {

  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
  private OffsetDateTime timestamp;

  private Long pageStartIndex = 0l;

  private Long pageSize = 100l;

  private Long totalResults;

  @Valid
  private List<@Valid TeaProduct> results = new ArrayList<>();

  public TeaInlineObject() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public TeaInlineObject(OffsetDateTime timestamp, Long pageStartIndex, Long pageSize, Long totalResults) {
    this.timestamp = timestamp;
    this.pageStartIndex = pageStartIndex;
    this.pageSize = pageSize;
    this.totalResults = totalResults;
  }

  public TeaInlineObject timestamp(OffsetDateTime timestamp) {
    this.timestamp = timestamp;
    return this;
  }

  /**
   * Get timestamp
   * @return timestamp
   */
  @NotNull @Valid 
  @Schema(name = "timestamp", example = "2024-03-20T15:30:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("timestamp")
  public OffsetDateTime getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(OffsetDateTime timestamp) {
    this.timestamp = timestamp;
  }

  public TeaInlineObject pageStartIndex(Long pageStartIndex) {
    this.pageStartIndex = pageStartIndex;
    return this;
  }

  /**
   * Get pageStartIndex
   * @return pageStartIndex
   */
  @NotNull @Valid 
  @Schema(name = "pageStartIndex", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("pageStartIndex")
  public Long getPageStartIndex() {
    return pageStartIndex;
  }

  public void setPageStartIndex(Long pageStartIndex) {
    this.pageStartIndex = pageStartIndex;
  }

  public TeaInlineObject pageSize(Long pageSize) {
    this.pageSize = pageSize;
    return this;
  }

  /**
   * Get pageSize
   * @return pageSize
   */
  @NotNull @Valid 
  @Schema(name = "pageSize", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("pageSize")
  public Long getPageSize() {
    return pageSize;
  }

  public void setPageSize(Long pageSize) {
    this.pageSize = pageSize;
  }

  public TeaInlineObject totalResults(Long totalResults) {
    this.totalResults = totalResults;
    return this;
  }

  /**
   * Get totalResults
   * @return totalResults
   */
  @NotNull @Valid 
  @Schema(name = "totalResults", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("totalResults")
  public Long getTotalResults() {
    return totalResults;
  }

  public void setTotalResults(Long totalResults) {
    this.totalResults = totalResults;
  }

  public TeaInlineObject results(List<@Valid TeaProduct> results) {
    this.results = results;
    return this;
  }

  public TeaInlineObject addResultsItem(TeaProduct resultsItem) {
    if (this.results == null) {
      this.results = new ArrayList<>();
    }
    this.results.add(resultsItem);
    return this;
  }

  /**
   * Get results
   * @return results
   */
  @Valid 
  @Schema(name = "results", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("results")
  public List<@Valid TeaProduct> getResults() {
    return results;
  }

  public void setResults(List<@Valid TeaProduct> results) {
    this.results = results;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TeaInlineObject inlineObject = (TeaInlineObject) o;
    return Objects.equals(this.timestamp, inlineObject.timestamp) &&
        Objects.equals(this.pageStartIndex, inlineObject.pageStartIndex) &&
        Objects.equals(this.pageSize, inlineObject.pageSize) &&
        Objects.equals(this.totalResults, inlineObject.totalResults) &&
        Objects.equals(this.results, inlineObject.results);
  }

  @Override
  public int hashCode() {
    return Objects.hash(timestamp, pageStartIndex, pageSize, totalResults, results);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class InlineObject {\n");
    sb.append("    timestamp: ").append(toIndentedString(timestamp)).append("\n");
    sb.append("    pageStartIndex: ").append(toIndentedString(pageStartIndex)).append("\n");
    sb.append("    pageSize: ").append(toIndentedString(pageSize)).append("\n");
    sb.append("    totalResults: ").append(toIndentedString(totalResults)).append("\n");
    sb.append("    results: ").append(toIndentedString(results)).append("\n");
    sb.append("}");
    return sb.toString();
  }

  /**
   * Convert the given object to string with each line indented by 4 spaces
   * (except the first line).
   */
  private String toIndentedString(Object o) {
    if (o == null) {
      return "null";
    }
    return o.toString().replace("\n", "\n    ");
  }
}

