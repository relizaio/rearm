package io.reliza.model.tea;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import io.reliza.model.tea.TeaCollection;
import io.reliza.model.tea.TeaRelease;
import org.springframework.lang.Nullable;
import org.openapitools.jackson.nullable.JsonNullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * A TEA Component Release combined with its latest collection
 */

@Schema(name = "component-release-with-collection", description = "A TEA Component Release combined with its latest collection")
@JsonTypeName("component-release-with-collection")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2025-10-15T13:35:56.249199300-04:00[America/Toronto]", comments = "Generator version: 7.14.0")
public class TeaComponentReleaseWithCollection {

  private TeaRelease release;

  private TeaCollection latestCollection;

  public TeaComponentReleaseWithCollection() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public TeaComponentReleaseWithCollection(TeaRelease release, TeaCollection latestCollection) {
    this.release = release;
    this.latestCollection = latestCollection;
  }

  public TeaComponentReleaseWithCollection release(TeaRelease release) {
    this.release = release;
    return this;
  }

  /**
   * The TEA Component Release information
   * @return release
   */
  @NotNull @Valid 
  @Schema(name = "release", description = "The TEA Component Release information", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("release")
  public TeaRelease getRelease() {
    return release;
  }

  public void setRelease(TeaRelease release) {
    this.release = release;
  }

  public TeaComponentReleaseWithCollection latestCollection(TeaCollection latestCollection) {
    this.latestCollection = latestCollection;
    return this;
  }

  /**
   * The latest TEA Collection for this component release
   * @return latestCollection
   */
  @NotNull @Valid 
  @Schema(name = "latestCollection", description = "The latest TEA Collection for this component release", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("latestCollection")
  public TeaCollection getLatestCollection() {
    return latestCollection;
  }

  public void setLatestCollection(TeaCollection latestCollection) {
    this.latestCollection = latestCollection;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TeaComponentReleaseWithCollection componentReleaseWithCollection = (TeaComponentReleaseWithCollection) o;
    return Objects.equals(this.release, componentReleaseWithCollection.release) &&
        Objects.equals(this.latestCollection, componentReleaseWithCollection.latestCollection);
  }

  @Override
  public int hashCode() {
    return Objects.hash(release, latestCollection);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class TeaComponentReleaseWithCollection {\n");
    sb.append("    release: ").append(toIndentedString(release)).append("\n");
    sb.append("    latestCollection: ").append(toIndentedString(latestCollection)).append("\n");
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

