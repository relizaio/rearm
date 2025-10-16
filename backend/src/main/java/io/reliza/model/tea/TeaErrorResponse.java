package io.reliza.model.tea;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.annotation.JsonValue;
import io.reliza.model.tea.TeaUnknownErrorType;
import org.springframework.lang.Nullable;
import org.openapitools.jackson.nullable.JsonNullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * Error response
 */

@Schema(name = "error-response", description = "Error response")
@JsonTypeName("error-response")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2025-10-15T13:35:56.249199300-04:00[America/Toronto]", comments = "Generator version: 7.14.0")
public class TeaErrorResponse {

  private TeaUnknownErrorType error;

  public TeaErrorResponse() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public TeaErrorResponse(TeaUnknownErrorType error) {
    this.error = error;
  }

  public TeaErrorResponse error(TeaUnknownErrorType error) {
    this.error = error;
    return this;
  }

  /**
   * Get error
   * @return error
   */
  @NotNull @Valid 
  @Schema(name = "error", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("error")
  public TeaUnknownErrorType getError() {
    return error;
  }

  public void setError(TeaUnknownErrorType error) {
    this.error = error;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TeaErrorResponse errorResponse = (TeaErrorResponse) o;
    return Objects.equals(this.error, errorResponse.error);
  }

  @Override
  public int hashCode() {
    return Objects.hash(error);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class TeaErrorResponse {\n");
    sb.append("    error: ").append(toIndentedString(error)).append("\n");
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

