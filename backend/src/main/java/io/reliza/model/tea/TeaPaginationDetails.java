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
 * PaginationDetails
 */

@JsonTypeName("pagination-details")
@Generated(value = "io.reliza.codegen.languages.SpringCodegen", date = "2025-05-08T09:03:56.085827200-04:00[America/Toronto]", comments = "Generator version: 7.13.0")
public class TeaPaginationDetails {

  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
  private OffsetDateTime timestamp;

  private Long pageStartIndex = 0l;

  private Long pageSize = 100l;

  private Long totalResults;

  public TeaPaginationDetails() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public TeaPaginationDetails(OffsetDateTime timestamp, Long pageStartIndex, Long pageSize, Long totalResults) {
    this.timestamp = timestamp;
    this.pageStartIndex = pageStartIndex;
    this.pageSize = pageSize;
    this.totalResults = totalResults;
  }

  public TeaPaginationDetails timestamp(OffsetDateTime timestamp) {
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

  public TeaPaginationDetails pageStartIndex(Long pageStartIndex) {
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

  public TeaPaginationDetails pageSize(Long pageSize) {
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

  public TeaPaginationDetails totalResults(Long totalResults) {
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

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TeaPaginationDetails paginationDetails = (TeaPaginationDetails) o;
    return Objects.equals(this.timestamp, paginationDetails.timestamp) &&
        Objects.equals(this.pageStartIndex, paginationDetails.pageStartIndex) &&
        Objects.equals(this.pageSize, paginationDetails.pageSize) &&
        Objects.equals(this.totalResults, paginationDetails.totalResults);
  }

  @Override
  public int hashCode() {
    return Objects.hash(timestamp, pageStartIndex, pageSize, totalResults);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class PaginationDetails {\n");
    sb.append("    timestamp: ").append(toIndentedString(timestamp)).append("\n");
    sb.append("    pageStartIndex: ").append(toIndentedString(pageStartIndex)).append("\n");
    sb.append("    pageSize: ").append(toIndentedString(pageSize)).append("\n");
    sb.append("    totalResults: ").append(toIndentedString(totalResults)).append("\n");
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

