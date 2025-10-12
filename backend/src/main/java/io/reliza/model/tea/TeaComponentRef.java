package io.reliza.model.tea;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import java.util.UUID;
import org.springframework.lang.Nullable;
import org.openapitools.jackson.nullable.JsonNullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * A reference to a TEA component or specific component release
 */

@Schema(name = "component-ref", description = "A reference to a TEA component or specific component release")
@JsonTypeName("component-ref")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2025-10-11T15:33:29.932635600-04:00[America/Toronto]", comments = "Generator version: 7.14.0")
public class TeaComponentRef {

  private UUID uuid;

  private @Nullable UUID release;

  public TeaComponentRef() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public TeaComponentRef(UUID uuid) {
    this.uuid = uuid;
  }

  public TeaComponentRef uuid(UUID uuid) {
    this.uuid = uuid;
    return this;
  }

  /**
   * A UUID
   * @return uuid
   */
  @NotNull @Valid 
  @Schema(name = "uuid", description = "A UUID", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("uuid")
  public UUID getUuid() {
    return uuid;
  }

  public void setUuid(UUID uuid) {
    this.uuid = uuid;
  }

  public TeaComponentRef release(@Nullable UUID release) {
    this.release = release;
    return this;
  }

  /**
   * A UUID
   * @return release
   */
  @Valid 
  @Schema(name = "release", description = "A UUID", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("release")
  public @Nullable UUID getRelease() {
    return release;
  }

  public void setRelease(@Nullable UUID release) {
    this.release = release;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TeaComponentRef componentRef = (TeaComponentRef) o;
    return Objects.equals(this.uuid, componentRef.uuid) &&
        Objects.equals(this.release, componentRef.release);
  }

  @Override
  public int hashCode() {
    return Objects.hash(uuid, release);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class TeaComponentRef {\n");
    sb.append("    uuid: ").append(toIndentedString(uuid)).append("\n");
    sb.append("    release: ").append(toIndentedString(release)).append("\n");
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

