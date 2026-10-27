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
 * Which versions an event affects. Exactly one of &#x60;version&#x60; or &#x60;range&#x60;. &#x60;range&#x60; is an ECMA-428 version specification (&#x60;vers&#x60;). &#x60;version&#x60; names a single version without a &#x60;vers&#x60; scheme and is a TEA extension: it maps to &#x60;vers:&lt;scheme&gt;/&lt;version&gt;&#x60; when the ecosystem is known. 
 */

@Schema(name = "cle-version-specifier", description = "Which versions an event affects. Exactly one of `version` or `range`. `range` is an ECMA-428 version specification (`vers`). `version` names a single version without a `vers` scheme and is a TEA extension: it maps to `vers:<scheme>/<version>` when the ecosystem is known. ")
@JsonTypeName("cle-version-specifier")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2026-10-08T02:50:49.497696677Z[Etc/UTC]", comments = "Generator version: 7.21.0")
public class TeaCleVersionSpecifier {

  private @Nullable String version;

  private @Nullable String range;

  public TeaCleVersionSpecifier version(@Nullable String version) {
    this.version = version;
    return this;
  }

  /**
   * A specific version string
   * @return version
   */
  
  @Schema(name = "version", description = "A specific version string", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("version")
  public @Nullable String getVersion() {
    return version;
  }

  @JsonProperty("version")
  public void setVersion(@Nullable String version) {
    this.version = version;
  }

  public TeaCleVersionSpecifier range(@Nullable String range) {
    this.range = range;
    return this;
  }

  /**
   * A version range in vers format (e.g. \"vers:npm/>=1.0.0|<2.0.0\")
   * @return range
   */
  
  @Schema(name = "range", description = "A version range in vers format (e.g. \"vers:npm/>=1.0.0|<2.0.0\")", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("range")
  public @Nullable String getRange() {
    return range;
  }

  @JsonProperty("range")
  public void setRange(@Nullable String range) {
    this.range = range;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TeaCleVersionSpecifier cleVersionSpecifier = (TeaCleVersionSpecifier) o;
    return Objects.equals(this.version, cleVersionSpecifier.version) &&
        Objects.equals(this.range, cleVersionSpecifier.range);
  }

  @Override
  public int hashCode() {
    return Objects.hash(version, range);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class TeaCleVersionSpecifier {\n");
    sb.append("    version: ").append(toIndentedString(version)).append("\n");
    sb.append("    range: ").append(toIndentedString(range)).append("\n");
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

