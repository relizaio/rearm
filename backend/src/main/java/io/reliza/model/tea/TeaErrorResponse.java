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
 * TEA API error body. Servers shall emit exactly this shape: &#x60;error&#x60; is one of the values of &#x60;unknown-error-type&#x60;, and &#x60;message&#x60; is the only other property allowed. Clients recognize a TEA error by an &#x60;application/json&#x60; response whose body is a JSON object with a string &#x60;error&#x60; property. Other JSON 404 bodies are not TEA error responses.  Any &#x60;4xx&#x60; from a resource endpoint may carry an &#x60;error-response&#x60; body; when present, &#x60;error&#x60; is from &#x60;unknown-error-type&#x60;. Clients shall not require a body. 
 */

@Schema(name = "error-response", description = "TEA API error body. Servers shall emit exactly this shape: `error` is one of the values of `unknown-error-type`, and `message` is the only other property allowed. Clients recognize a TEA error by an `application/json` response whose body is a JSON object with a string `error` property. Other JSON 404 bodies are not TEA error responses.  Any `4xx` from a resource endpoint may carry an `error-response` body; when present, `error` is from `unknown-error-type`. Clients shall not require a body. ")
@JsonTypeName("error-response")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2026-10-08T02:50:49.497696677Z[Etc/UTC]", comments = "Generator version: 7.21.0")
public class TeaErrorResponse {

  private TeaUnknownErrorType error;

  private @Nullable String message;

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

  @JsonProperty("error")
  public void setError(TeaUnknownErrorType error) {
    this.error = error;
  }

  public TeaErrorResponse message(@Nullable String message) {
    this.message = message;
    return this;
  }

  /**
   * Optional human-readable explanation for diagnostics. Clients shall not interpret it programmatically. 
   * @return message
   */
  
  @Schema(name = "message", description = "Optional human-readable explanation for diagnostics. Clients shall not interpret it programmatically. ", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("message")
  public @Nullable String getMessage() {
    return message;
  }

  @JsonProperty("message")
  public void setMessage(@Nullable String message) {
    this.message = message;
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
    return Objects.equals(this.error, errorResponse.error) &&
        Objects.equals(this.message, errorResponse.message);
  }

  @Override
  public int hashCode() {
    return Objects.hash(error, message);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class TeaErrorResponse {\n");
    sb.append("    error: ").append(toIndentedString(error)).append("\n");
    sb.append("    message: ").append(toIndentedString(message)).append("\n");
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

