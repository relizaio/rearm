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
 * Lifecycle events for the object this endpoint is called on, as a TEA projection of the Common Lifecycle Enumeration (ECMA-428, 1st edition).  This is not a CLE document. The document envelope (&#x60;$schema&#x60;, &#x60;identifier&#x60;, &#x60;updatedAt&#x60;, and &#x60;index&#x60; / &#x60;next&#x60; page chaining) is not carried: the TEA object the response belongs to supplies the identity, and TEA has its own pagination. The event vocabulary, ordering and semantics are those of ECMA-428; the field shapes are TEA&#39;s. A consumer that needs a CLE document applies the mapping rules below and adds the envelope; a producer that holds a CLE document applies them in reverse. Publishers that need a bit-identical CLE 1.0.0 document publish it as a TEA Artifact in a release collection, as artifact type &#x60;OTHER&#x60; until a dedicated type exists; the same artifact may be referenced from more than one collection. The CLE endpoints are not a substitute for that document.  Mapping to ECMA-428: - &#x60;identifiers[]&#x60; on &#x60;componentRenamed&#x60; uses the TEA &#x60;identifier&#x60; shape (&#x60;idType&#x60;,   &#x60;idValue&#x60;); CLE uses &#x60;type&#x60; / &#x60;value&#x60; with the same meaning. - A version specifier &#x60;{ version }&#x60; names one version without a &#x60;vers&#x60; scheme,   because a TEA server does not always know the package ecosystem. It corresponds to   the CLE range &#x60;vers:&lt;scheme&gt;/&lt;version&gt;&#x60; once the scheme is known. &#x60;{ range }&#x60; is a   CLE range as-is. - &#x60;cle-event&#x60; is one flat object. The fields ECMA-428 requires or permits for each   event type apply as written there; servers shall populate the required ones and   omit fields that do not belong to the type.  Selection. Servers shall return in &#x60;events&#x60; a subset of the events of the CLE data they hold for the component or product, and shall not alter an event beyond the mapping above. On a release endpoint, servers may omit events scoped to other versions: events whose &#x60;versions&#x60; do not cover the release&#39;s &#x60;version&#x60;, and &#x60;released&#x60; events whose &#x60;version&#x60; differs from it. Events that name no version, other than &#x60;withdrawn&#x60; events, apply to the component as a whole and are retained. Inclusion of &#x60;withdrawn&#x60; events follows the rule below. Servers shall not otherwise reduce the set, and in particular shall not collapse it to the latest event per type. Servers shall not renumber event &#x60;id&#x60; values. Servers shall include a &#x60;withdrawn&#x60; event if, and only if, they include the event it withdraws. &#x60;definitions.support&#x60; shall contain at least the policies referenced by the included events.  &#x60;events&#x60; shall be ordered by &#x60;id&#x60; descending, as in ECMA-428. A server that provides CLE for an object answers &#x60;200&#x60;, with an empty &#x60;events&#x60; array when no event has been recorded yet. A server that does not provide CLE for the object answers &#x60;404&#x60; with &#x60;error: NOT_IMPLEMENTED&#x60;; &#x60;error: OBJECT_UNKNOWN&#x60; means the object itself is unknown. 
 */

@Schema(name = "cle", description = "Lifecycle events for the object this endpoint is called on, as a TEA projection of the Common Lifecycle Enumeration (ECMA-428, 1st edition).  This is not a CLE document. The document envelope (`$schema`, `identifier`, `updatedAt`, and `index` / `next` page chaining) is not carried: the TEA object the response belongs to supplies the identity, and TEA has its own pagination. The event vocabulary, ordering and semantics are those of ECMA-428; the field shapes are TEA's. A consumer that needs a CLE document applies the mapping rules below and adds the envelope; a producer that holds a CLE document applies them in reverse. Publishers that need a bit-identical CLE 1.0.0 document publish it as a TEA Artifact in a release collection, as artifact type `OTHER` until a dedicated type exists; the same artifact may be referenced from more than one collection. The CLE endpoints are not a substitute for that document.  Mapping to ECMA-428: - `identifiers[]` on `componentRenamed` uses the TEA `identifier` shape (`idType`,   `idValue`); CLE uses `type` / `value` with the same meaning. - A version specifier `{ version }` names one version without a `vers` scheme,   because a TEA server does not always know the package ecosystem. It corresponds to   the CLE range `vers:<scheme>/<version>` once the scheme is known. `{ range }` is a   CLE range as-is. - `cle-event` is one flat object. The fields ECMA-428 requires or permits for each   event type apply as written there; servers shall populate the required ones and   omit fields that do not belong to the type.  Selection. Servers shall return in `events` a subset of the events of the CLE data they hold for the component or product, and shall not alter an event beyond the mapping above. On a release endpoint, servers may omit events scoped to other versions: events whose `versions` do not cover the release's `version`, and `released` events whose `version` differs from it. Events that name no version, other than `withdrawn` events, apply to the component as a whole and are retained. Inclusion of `withdrawn` events follows the rule below. Servers shall not otherwise reduce the set, and in particular shall not collapse it to the latest event per type. Servers shall not renumber event `id` values. Servers shall include a `withdrawn` event if, and only if, they include the event it withdraws. `definitions.support` shall contain at least the policies referenced by the included events.  `events` shall be ordered by `id` descending, as in ECMA-428. A server that provides CLE for an object answers `200`, with an empty `events` array when no event has been recorded yet. A server that does not provide CLE for the object answers `404` with `error: NOT_IMPLEMENTED`; `error: OBJECT_UNKNOWN` means the object itself is unknown. ")
@JsonTypeName("cle")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2026-10-08T02:50:49.497696677Z[Etc/UTC]", comments = "Generator version: 7.21.0")
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
   * Array of CLE Event objects representing lifecycle events. Events shall be ordered by id descending (newest events with highest IDs first). 
   * @return events
   */
  @NotNull @Valid 
  @Schema(name = "events", description = "Array of CLE Event objects representing lifecycle events. Events shall be ordered by id descending (newest events with highest IDs first). ", requiredMode = Schema.RequiredMode.REQUIRED)
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

