package io.reliza.model.tea;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import io.reliza.model.tea.TeaProduct;
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
 * A paginated response containing TEA Products
 */

@Schema(name = "paginated-product-response", description = "A paginated response containing TEA Products")
@JsonTypeName("paginated-product-response")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2026-10-08T02:50:49.497696677Z[Etc/UTC]", comments = "Generator version: 7.21.0")
public class TeaPaginatedProductResponse {

  private Boolean hasNext = false;

  private @Nullable String nextPageToken;

  @Valid
  private List<@Valid TeaProduct> results = new ArrayList<>();

  public TeaPaginatedProductResponse() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public TeaPaginatedProductResponse(Boolean hasNext, List<@Valid TeaProduct> results) {
    this.hasNext = hasNext;
    this.results = results;
  }

  public TeaPaginatedProductResponse hasNext(Boolean hasNext) {
    this.hasNext = hasNext;
    return this;
  }

  /**
   * A flag indicating whether there is another page of results to fetch.  When `hasNext` is `true`, `nextPageToken` shall be present. When `hasNext` is `false`, `nextPageToken` shall not be included. 
   * @return hasNext
   */
  @NotNull 
  @Schema(name = "hasNext", description = "A flag indicating whether there is another page of results to fetch.  When `hasNext` is `true`, `nextPageToken` shall be present. When `hasNext` is `false`, `nextPageToken` shall not be included. ", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("hasNext")
  public Boolean getHasNext() {
    return hasNext;
  }

  @JsonProperty("hasNext")
  public void setHasNext(Boolean hasNext) {
    this.hasNext = hasNext;
  }

  public TeaPaginatedProductResponse nextPageToken(@Nullable String nextPageToken) {
    this.nextPageToken = nextPageToken;
    return this;
  }

  /**
   * An opaque token that can be used in a following request to retrieve the next page of results.  This field shall be present when `hasNext` is `true`. This field shall not be included when `hasNext` is `false`. 
   * @return nextPageToken
   */
  
  @Schema(name = "nextPageToken", description = "An opaque token that can be used in a following request to retrieve the next page of results.  This field shall be present when `hasNext` is `true`. This field shall not be included when `hasNext` is `false`. ", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("nextPageToken")
  public @Nullable String getNextPageToken() {
    return nextPageToken;
  }

  @JsonProperty("nextPageToken")
  public void setNextPageToken(@Nullable String nextPageToken) {
    this.nextPageToken = nextPageToken;
  }

  public TeaPaginatedProductResponse results(List<@Valid TeaProduct> results) {
    this.results = results;
    return this;
  }

  public TeaPaginatedProductResponse addResultsItem(TeaProduct resultsItem) {
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
  public List<@Valid TeaProduct> getResults() {
    return results;
  }

  @JsonProperty("results")
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
    TeaPaginatedProductResponse paginatedProductResponse = (TeaPaginatedProductResponse) o;
    return Objects.equals(this.hasNext, paginatedProductResponse.hasNext) &&
        Objects.equals(this.nextPageToken, paginatedProductResponse.nextPageToken) &&
        Objects.equals(this.results, paginatedProductResponse.results);
  }

  @Override
  public int hashCode() {
    return Objects.hash(hasNext, nextPageToken, results);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class TeaPaginatedProductResponse {\n");
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

