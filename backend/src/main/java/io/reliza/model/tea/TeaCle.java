package io.reliza.model.tea;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import io.reliza.model.tea.TeaCleDefinitions;
import io.reliza.model.tea.TeaCleEvent;
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
 * Common Lifecycle Enumeration (CLE) object based on ECMA-428 TC54 TG3 CLE Specification v1.0.0. Contains lifecycle events and optional reusable definitions for a component or product. 
 */

@Schema(name = "cle", description = "Common Lifecycle Enumeration (CLE) object based on ECMA-428 TC54 TG3 CLE Specification v1.0.0. Contains lifecycle events and optional reusable definitions for a component or product. ")
@JsonTypeName("cle")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2026-03-29T10:44:15.267909500-04:00[America/Toronto]", comments = "Generator version: 7.21.0")
public class TeaCle {

  @Valid
  private List<@Valid TeaCleEvent> events = new ArrayList<>();

  private @Nullable TeaCleDefinitions definitions;

  public TeaCle() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public TeaCle(List<@Valid TeaCleEvent> events) {
    this.events = events;
  }

  public TeaCle events(List<@Valid TeaCleEvent> events) {
    this.events = events;
    return this;
  }

  public TeaCle addEventsItem(TeaCleEvent eventsItem) {
    if (this.events == null) {
      this.events = new ArrayList<>();
    }
    this.events.add(eventsItem);
    return this;
  }

  /**
   * Ordered array of CLE Event objects representing lifecycle events. MUST be ordered by ID in descending order (newest events with highest IDs first). 
   * @return events
   */
  @NotNull @Valid 
  @Schema(name = "events", description = "Ordered array of CLE Event objects representing lifecycle events. MUST be ordered by ID in descending order (newest events with highest IDs first). ", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("events")
  public List<@Valid TeaCleEvent> getEvents() {
    return events;
  }

  @JsonProperty("events")
  public void setEvents(List<@Valid TeaCleEvent> events) {
    this.events = events;
  }

  public TeaCle definitions(@Nullable TeaCleDefinitions definitions) {
    this.definitions = definitions;
    return this;
  }

  /**
   * Container for reusable policy definitions referenced by events
   * @return definitions
   */
  @Valid 
  @Schema(name = "definitions", description = "Container for reusable policy definitions referenced by events", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("definitions")
  public @Nullable TeaCleDefinitions getDefinitions() {
    return definitions;
  }

  @JsonProperty("definitions")
  public void setDefinitions(@Nullable TeaCleDefinitions definitions) {
    this.definitions = definitions;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TeaCle cle = (TeaCle) o;
    return Objects.equals(this.events, cle.events) &&
        Objects.equals(this.definitions, cle.definitions);
  }

  @Override
  public int hashCode() {
    return Objects.hash(events, definitions);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class TeaCle {\n");
    sb.append("    events: ").append(toIndentedString(events)).append("\n");
    sb.append("    definitions: ").append(toIndentedString(definitions)).append("\n");
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

