package io.reliza.model.tea;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.annotation.JsonValue;
import org.springframework.lang.Nullable;
import org.openapitools.jackson.nullable.JsonNullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * Successful token response, as defined in RFC 6749 section 5.1.
 */

@Schema(name = "token-response", description = "Successful token response, as defined in RFC 6749 section 5.1.")
@JsonTypeName("token-response")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2026-10-08T02:50:49.497696677Z[Etc/UTC]", comments = "Generator version: 7.21.0")
public class TeaTokenResponse {

  private String accessToken;

  /**
   * Token type. Always `Bearer` in TEA.
   */
  public enum TokenTypeEnum {
    BEARER("Bearer");

    private final String value;

    TokenTypeEnum(String value) {
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
    public static TokenTypeEnum fromValue(String value) {
      for (TokenTypeEnum b : TokenTypeEnum.values()) {
        if (b.value.equals(value)) {
          return b;
        }
      }
      throw new IllegalArgumentException("Unexpected value '" + value + "'");
    }
  }

  private TokenTypeEnum tokenType;

  private @Nullable Integer expiresIn;

  private @Nullable String scope;

  public TeaTokenResponse() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public TeaTokenResponse(String accessToken, TokenTypeEnum tokenType) {
    this.accessToken = accessToken;
    this.tokenType = tokenType;
  }

  public TeaTokenResponse accessToken(String accessToken) {
    this.accessToken = accessToken;
    return this;
  }

  /**
   * The issued access token, presented on other TEA endpoints as `Authorization: Bearer <access_token>`. Opaque to the client. 
   * @return accessToken
   */
  @NotNull 
  @Schema(name = "access_token", example = "2YotnFZFEjr1zCsicMWpAA", description = "The issued access token, presented on other TEA endpoints as `Authorization: Bearer <access_token>`. Opaque to the client. ", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("access_token")
  public String getAccessToken() {
    return accessToken;
  }

  @JsonProperty("access_token")
  public void setAccessToken(String accessToken) {
    this.accessToken = accessToken;
  }

  public TeaTokenResponse tokenType(TokenTypeEnum tokenType) {
    this.tokenType = tokenType;
    return this;
  }

  /**
   * Token type. Always `Bearer` in TEA.
   * @return tokenType
   */
  @NotNull 
  @Schema(name = "token_type", description = "Token type. Always `Bearer` in TEA.", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("token_type")
  public TokenTypeEnum getTokenType() {
    return tokenType;
  }

  @JsonProperty("token_type")
  public void setTokenType(TokenTypeEnum tokenType) {
    this.tokenType = tokenType;
  }

  public TeaTokenResponse expiresIn(@Nullable Integer expiresIn) {
    this.expiresIn = expiresIn;
    return this;
  }

  /**
   * Lifetime of the access token in seconds. Servers should include this so that clients can re-authenticate before expiry rather than on failure. 
   * @return expiresIn
   */
  
  @Schema(name = "expires_in", example = "3600", description = "Lifetime of the access token in seconds. Servers should include this so that clients can re-authenticate before expiry rather than on failure. ", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("expires_in")
  public @Nullable Integer getExpiresIn() {
    return expiresIn;
  }

  @JsonProperty("expires_in")
  public void setExpiresIn(@Nullable Integer expiresIn) {
    this.expiresIn = expiresIn;
  }

  public TeaTokenResponse scope(@Nullable String scope) {
    this.scope = scope;
    return this;
  }

  /**
   * Granted scope, when it differs from the scope requested.
   * @return scope
   */
  
  @Schema(name = "scope", description = "Granted scope, when it differs from the scope requested.", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("scope")
  public @Nullable String getScope() {
    return scope;
  }

  @JsonProperty("scope")
  public void setScope(@Nullable String scope) {
    this.scope = scope;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TeaTokenResponse tokenResponse = (TeaTokenResponse) o;
    return Objects.equals(this.accessToken, tokenResponse.accessToken) &&
        Objects.equals(this.tokenType, tokenResponse.tokenType) &&
        Objects.equals(this.expiresIn, tokenResponse.expiresIn) &&
        Objects.equals(this.scope, tokenResponse.scope);
  }

  @Override
  public int hashCode() {
    return Objects.hash(accessToken, tokenType, expiresIn, scope);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class TeaTokenResponse {\n");
    sb.append("    accessToken: ").append(toIndentedString(accessToken)).append("\n");
    sb.append("    tokenType: ").append(toIndentedString(tokenType)).append("\n");
    sb.append("    expiresIn: ").append(toIndentedString(expiresIn)).append("\n");
    sb.append("    scope: ").append(toIndentedString(scope)).append("\n");
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

