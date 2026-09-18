package io.reliza.model.tea;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import org.springframework.lang.Nullable;
import org.openapitools.jackson.nullable.JsonNullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * TeaPaginationDetails
 */

@JsonTypeName("pagination-details")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2026-06-03T01:14:38.691100265Z[Etc/UTC]", comments = "Generator version: 7.21.0")
public class TeaPaginationDetails {

  private Boolean hasNext = false;

  private String nextPageToken;

  public TeaPaginationDetails() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public TeaPaginationDetails(Boolean hasNext, String nextPageToken) {
    this.hasNext = hasNext;
    this.nextPageToken = nextPageToken;
  }

  public TeaPaginationDetails hasNext(Boolean hasNext) {
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

  public TeaPaginationDetails nextPageToken(String nextPageToken) {
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

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TeaPaginationDetails paginationDetails = (TeaPaginationDetails) o;
    return Objects.equals(this.hasNext, paginationDetails.hasNext) &&
        Objects.equals(this.nextPageToken, paginationDetails.nextPageToken);
  }

  @Override
  public int hashCode() {
    return Objects.hash(hasNext, nextPageToken);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class TeaPaginationDetails {\n");
    sb.append("    hasNext: ").append(toIndentedString(hasNext)).append("\n");
    sb.append("    nextPageToken: ").append(toIndentedString(nextPageToken)).append("\n");
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

