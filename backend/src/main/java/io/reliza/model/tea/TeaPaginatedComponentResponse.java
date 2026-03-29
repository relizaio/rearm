package io.reliza.model.tea;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import io.reliza.model.tea.TeaComponent;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
 * A paginated response containing TEA Components
 */

@Schema(name = "paginated-component-response", description = "A paginated response containing TEA Components")
@JsonTypeName("paginated-component-response")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2026-03-29T10:44:15.267909500-04:00[America/Toronto]", comments = "Generator version: 7.21.0")
public class TeaPaginatedComponentResponse {

  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
  private OffsetDateTime timestamp;

  private Long pageStartIndex = 0l;

  private Long pageSize = 100l;

  private Long totalResults;

  @Valid
  private List<@Valid TeaComponent> results = new ArrayList<>();

  public TeaPaginatedComponentResponse() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public TeaPaginatedComponentResponse(OffsetDateTime timestamp, Long pageStartIndex, Long pageSize, Long totalResults) {
    this.timestamp = timestamp;
    this.pageStartIndex = pageStartIndex;
    this.pageSize = pageSize;
    this.totalResults = totalResults;
  }

  public TeaPaginatedComponentResponse timestamp(OffsetDateTime timestamp) {
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

  @JsonProperty("timestamp")
  public void setTimestamp(OffsetDateTime timestamp) {
    this.timestamp = timestamp;
  }

  public TeaPaginatedComponentResponse pageStartIndex(Long pageStartIndex) {
    this.pageStartIndex = pageStartIndex;
    return this;
  }

  /**
   * Get pageStartIndex
   * @return pageStartIndex
   */
  @NotNull 
  @Schema(name = "pageStartIndex", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("pageStartIndex")
  public Long getPageStartIndex() {
    return pageStartIndex;
  }

  @JsonProperty("pageStartIndex")
  public void setPageStartIndex(Long pageStartIndex) {
    this.pageStartIndex = pageStartIndex;
  }

  public TeaPaginatedComponentResponse pageSize(Long pageSize) {
    this.pageSize = pageSize;
    return this;
  }

  /**
   * Get pageSize
   * @return pageSize
   */
  @NotNull 
  @Schema(name = "pageSize", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("pageSize")
  public Long getPageSize() {
    return pageSize;
  }

  @JsonProperty("pageSize")
  public void setPageSize(Long pageSize) {
    this.pageSize = pageSize;
  }

  public TeaPaginatedComponentResponse totalResults(Long totalResults) {
    this.totalResults = totalResults;
    return this;
  }

  /**
   * Get totalResults
   * @return totalResults
   */
  @NotNull 
  @Schema(name = "totalResults", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("totalResults")
  public Long getTotalResults() {
    return totalResults;
  }

  @JsonProperty("totalResults")
  public void setTotalResults(Long totalResults) {
    this.totalResults = totalResults;
  }

  public TeaPaginatedComponentResponse results(List<@Valid TeaComponent> results) {
    this.results = results;
    return this;
  }

  public TeaPaginatedComponentResponse addResultsItem(TeaComponent resultsItem) {
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
  public List<@Valid TeaComponent> getResults() {
    return results;
  }

  @JsonProperty("results")
  public void setResults(List<@Valid TeaComponent> results) {
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
    TeaPaginatedComponentResponse paginatedComponentResponse = (TeaPaginatedComponentResponse) o;
    return Objects.equals(this.timestamp, paginatedComponentResponse.timestamp) &&
        Objects.equals(this.pageStartIndex, paginatedComponentResponse.pageStartIndex) &&
        Objects.equals(this.pageSize, paginatedComponentResponse.pageSize) &&
        Objects.equals(this.totalResults, paginatedComponentResponse.totalResults) &&
        Objects.equals(this.results, paginatedComponentResponse.results);
  }

  @Override
  public int hashCode() {
    return Objects.hash(timestamp, pageStartIndex, pageSize, totalResults, results);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class TeaPaginatedComponentResponse {\n");
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
  private String toIndentedString(@Nullable Object o) {
    return o == null ? "null" : o.toString().replace("\n", "\n    ");
  }
}

