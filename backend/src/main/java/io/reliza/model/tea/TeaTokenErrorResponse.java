package io.reliza.model.tea;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.annotation.JsonValue;
import java.net.URI;
import org.springframework.lang.Nullable;
import org.openapitools.jackson.nullable.JsonNullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * Token error response, as defined in RFC 6749 section 5.2.
 */

@Schema(name = "token-error-response", description = "Token error response, as defined in RFC 6749 section 5.2.")
@JsonTypeName("token-error-response")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2026-10-08T02:50:49.497696677Z[Etc/UTC]", comments = "Generator version: 7.21.0")
public class TeaTokenErrorResponse {

  /**
   * Error code as defined in RFC 6749 section 5.2.
   */
  public enum ErrorEnum {
    INVALID_REQUEST("invalid_request"),
    
    INVALID_CLIENT("invalid_client"),
    
    INVALID_GRANT("invalid_grant"),
    
    UNAUTHORIZED_CLIENT("unauthorized_client"),
    
    UNSUPPORTED_GRANT_TYPE("unsupported_grant_type"),
    
    INVALID_SCOPE("invalid_scope");

    private final String value;

    ErrorEnum(String value) {
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
    public static ErrorEnum fromValue(String value) {
      for (ErrorEnum b : ErrorEnum.values()) {
        if (b.value.equals(value)) {
          return b;
        }
      }
      throw new IllegalArgumentException("Unexpected value '" + value + "'");
    }
  }

  private ErrorEnum error;

  private @Nullable String errorDescription;

  private @Nullable URI errorUri;

  public TeaTokenErrorResponse() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public TeaTokenErrorResponse(ErrorEnum error) {
    this.error = error;
  }

  public TeaTokenErrorResponse error(ErrorEnum error) {
    this.error = error;
    return this;
  }

  /**
   * Error code as defined in RFC 6749 section 5.2.
   * @return error
   */
  @NotNull 
  @Schema(name = "error", description = "Error code as defined in RFC 6749 section 5.2.", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("error")
  public ErrorEnum getError() {
    return error;
  }

  @JsonProperty("error")
  public void setError(ErrorEnum error) {
    this.error = error;
  }

  public TeaTokenErrorResponse errorDescription(@Nullable String errorDescription) {
    this.errorDescription = errorDescription;
    return this;
  }

  /**
   * Human-readable text providing additional information about the error.
   * @return errorDescription
   */
  
  @Schema(name = "error_description", description = "Human-readable text providing additional information about the error.", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("error_description")
  public @Nullable String getErrorDescription() {
    return errorDescription;
  }

  @JsonProperty("error_description")
  public void setErrorDescription(@Nullable String errorDescription) {
    this.errorDescription = errorDescription;
  }

  public TeaTokenErrorResponse errorUri(@Nullable URI errorUri) {
    this.errorUri = errorUri;
    return this;
  }

  /**
   * URI of a human-readable web page with information about the error.
   * @return errorUri
   */
  @Valid 
  @Schema(name = "error_uri", description = "URI of a human-readable web page with information about the error.", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("error_uri")
  public @Nullable URI getErrorUri() {
    return errorUri;
  }

  @JsonProperty("error_uri")
  public void setErrorUri(@Nullable URI errorUri) {
    this.errorUri = errorUri;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TeaTokenErrorResponse tokenErrorResponse = (TeaTokenErrorResponse) o;
    return Objects.equals(this.error, tokenErrorResponse.error) &&
        Objects.equals(this.errorDescription, tokenErrorResponse.errorDescription) &&
        Objects.equals(this.errorUri, tokenErrorResponse.errorUri);
  }

  @Override
  public int hashCode() {
    return Objects.hash(error, errorDescription, errorUri);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class TeaTokenErrorResponse {\n");
    sb.append("    error: ").append(toIndentedString(error)).append("\n");
    sb.append("    errorDescription: ").append(toIndentedString(errorDescription)).append("\n");
    sb.append("    errorUri: ").append(toIndentedString(errorUri)).append("\n");
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

