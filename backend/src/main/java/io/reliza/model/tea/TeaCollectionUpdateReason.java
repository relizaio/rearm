/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.tea;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.annotation.JsonValue;
import io.reliza.model.tea.TeaCollectionUpdateReasonType;
import org.springframework.lang.Nullable;
import org.openapitools.jackson.nullable.JsonNullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * Reason for the update to the TEA collection
 */

@Schema(name = "collection-update-reason", description = "Reason for the update to the TEA collection")
@JsonTypeName("collection-update-reason")
@Generated(value = "io.reliza.codegen.languages.SpringCodegen", date = "2025-05-08T09:03:56.085827200-04:00[America/Toronto]", comments = "Generator version: 7.13.0")
public class TeaCollectionUpdateReason {

  private @Nullable TeaCollectionUpdateReasonType type;

  private @Nullable String comment;

  public TeaCollectionUpdateReason type(TeaCollectionUpdateReasonType type) {
    this.type = type;
    return this;
  }

  /**
   * Type of update reason.
   * @return type
   */
  @Valid 
  @Schema(name = "type", description = "Type of update reason.", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("type")
  public TeaCollectionUpdateReasonType getType() {
    return type;
  }

  public void setType(TeaCollectionUpdateReasonType type) {
    this.type = type;
  }

  public TeaCollectionUpdateReason comment(String comment) {
    this.comment = comment;
    return this;
  }

  /**
   * Free text description
   * @return comment
   */
  
  @Schema(name = "comment", description = "Free text description", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("comment")
  public String getComment() {
    return comment;
  }

  public void setComment(String comment) {
    this.comment = comment;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TeaCollectionUpdateReason collectionUpdateReason = (TeaCollectionUpdateReason) o;
    return Objects.equals(this.type, collectionUpdateReason.type) &&
        Objects.equals(this.comment, collectionUpdateReason.comment);
  }

  @Override
  public int hashCode() {
    return Objects.hash(type, comment);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class CollectionUpdateReason {\n");
    sb.append("    type: ").append(toIndentedString(type)).append("\n");
    sb.append("    comment: ").append(toIndentedString(comment)).append("\n");
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

