package io.reliza.model.tea;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import io.reliza.model.tea.TeaCollection;
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
 * A paginated response containing TEA Collections
 */

@Schema(name = "paginated-collection-response", description = "A paginated response containing TEA Collections")
@JsonTypeName("paginated-collection-response")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2026-06-03T01:14:38.691100265Z[Etc/UTC]", comments = "Generator version: 7.21.0")
public class TeaPaginatedCollectionResponse {

  private Boolean hasNext = false;

  private String nextPageToken;

  @Valid
  private List<@Valid TeaCollection> results = new ArrayList<>();

  public TeaPaginatedCollectionResponse() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public TeaPaginatedCollectionResponse(Boolean hasNext, String nextPageToken, List<@Valid TeaCollection> results) {
    this.hasNext = hasNext;
    this.nextPageToken = nextPageToken;
    this.results = results;
  }

  public TeaPaginatedCollectionResponse hasNext(Boolean hasNext) {
    this.hasNext = hasNext;
    return this;
  }

  /**
   * A flag (to aid clients) to know whether there is a next page of results to fetch.   `nextPageToken` will always be supplied, hence this hint is included to aid clients. 
   * @return hasNext
   */
  @NotNull 
  @Schema(name = "hasNext", description = "A flag (to aid clients) to know whether there is a next page of results to fetch.   `nextPageToken` will always be supplied, hence this hint is included to aid clients. ", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("hasNext")
  public Boolean getHasNext() {
    return hasNext;
  }

  @JsonProperty("hasNext")
  public void setHasNext(Boolean hasNext) {
    this.hasNext = hasNext;
  }

  public TeaPaginatedCollectionResponse nextPageToken(String nextPageToken) {
    this.nextPageToken = nextPageToken;
    return this;
  }

  /**
   * A token that can be used in a following request to retrieve the next page or results.  It must always be supplied in responses. 
   * @return nextPageToken
   */
  @NotNull 
  @Schema(name = "nextPageToken", description = "A token that can be used in a following request to retrieve the next page or results.  It must always be supplied in responses. ", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("nextPageToken")
  public String getNextPageToken() {
    return nextPageToken;
  }

  @JsonProperty("nextPageToken")
  public void setNextPageToken(String nextPageToken) {
    this.nextPageToken = nextPageToken;
  }

  public TeaPaginatedCollectionResponse results(List<@Valid TeaCollection> results) {
    this.results = results;
    return this;
  }

  public TeaPaginatedCollectionResponse addResultsItem(TeaCollection resultsItem) {
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
  @NotNull @Valid 
  @Schema(name = "results", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("results")
  public List<@Valid TeaCollection> getResults() {
    return results;
  }

  @JsonProperty("results")
  public void setResults(List<@Valid TeaCollection> results) {
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
    TeaPaginatedCollectionResponse paginatedCollectionResponse = (TeaPaginatedCollectionResponse) o;
    return Objects.equals(this.hasNext, paginatedCollectionResponse.hasNext) &&
        Objects.equals(this.nextPageToken, paginatedCollectionResponse.nextPageToken) &&
        Objects.equals(this.results, paginatedCollectionResponse.results);
  }

  @Override
  public int hashCode() {
    return Objects.hash(hasNext, nextPageToken, results);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class TeaPaginatedCollectionResponse {\n");
    sb.append("    hasNext: ").append(toIndentedString(hasNext)).append("\n");
    sb.append("    nextPageToken: ").append(toIndentedString(nextPageToken)).append("\n");
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

