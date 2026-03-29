package io.reliza.model.tea;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.annotation.JsonValue;
import io.reliza.model.tea.TeaCleEventType;
import io.reliza.model.tea.TeaCleVersionSpecifier;
import io.reliza.model.tea.TeaIdentifier;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.lang.Nullable;
import org.openapitools.jackson.nullable.JsonNullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * A discrete lifecycle event from the CLE specification
 */

@Schema(name = "cle-event", description = "A discrete lifecycle event from the CLE specification")
@JsonTypeName("cle-event")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2026-03-29T10:44:15.267909500-04:00[America/Toronto]", comments = "Generator version: 7.21.0")
public class TeaCleEvent {

  private Integer id;

  private TeaCleEventType type;

  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
  private OffsetDateTime effective;

  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
  private OffsetDateTime published;

  private @Nullable String version;

  @Valid
  private List<@Valid TeaCleVersionSpecifier> versions = new ArrayList<>();

  private @Nullable String supportId;

  private @Nullable String license;

  private @Nullable String supersededByVersion;

  @Valid
  private List<@Valid TeaIdentifier> identifiers = new ArrayList<>();

  private @Nullable Integer eventId;

  private @Nullable String reason;

  private @Nullable String description;

  @Valid
  private List<URI> references = new ArrayList<>();

  public TeaCleEvent() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public TeaCleEvent(Integer id, TeaCleEventType type, OffsetDateTime effective, OffsetDateTime published) {
    this.id = id;
    this.type = type;
    this.effective = effective;
    this.published = published;
  }

  public TeaCleEvent id(Integer id) {
    this.id = id;
    return this;
  }

  /**
   * A unique, auto-incrementing integer identifier for the event
   * @return id
   */
  @NotNull 
  @Schema(name = "id", description = "A unique, auto-incrementing integer identifier for the event", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("id")
  public Integer getId() {
    return id;
  }

  @JsonProperty("id")
  public void setId(Integer id) {
    this.id = id;
  }

  public TeaCleEvent type(TeaCleEventType type) {
    this.type = type;
    return this;
  }

  /**
   * The type of lifecycle event
   * @return type
   */
  @NotNull @Valid 
  @Schema(name = "type", description = "The type of lifecycle event", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("type")
  public TeaCleEventType getType() {
    return type;
  }

  @JsonProperty("type")
  public void setType(TeaCleEventType type) {
    this.type = type;
  }

  public TeaCleEvent effective(OffsetDateTime effective) {
    this.effective = effective;
    return this;
  }

  /**
   * ISO 8601 timestamp (UTC) when the event takes effect
   * @return effective
   */
  @NotNull @Valid 
  @Schema(name = "effective", description = "ISO 8601 timestamp (UTC) when the event takes effect", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("effective")
  public OffsetDateTime getEffective() {
    return effective;
  }

  @JsonProperty("effective")
  public void setEffective(OffsetDateTime effective) {
    this.effective = effective;
  }

  public TeaCleEvent published(OffsetDateTime published) {
    this.published = published;
    return this;
  }

  /**
   * ISO 8601 timestamp (UTC) when the event was first published
   * @return published
   */
  @NotNull @Valid 
  @Schema(name = "published", description = "ISO 8601 timestamp (UTC) when the event was first published", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("published")
  public OffsetDateTime getPublished() {
    return published;
  }

  @JsonProperty("published")
  public void setPublished(OffsetDateTime published) {
    this.published = published;
  }

  public TeaCleEvent version(@Nullable String version) {
    this.version = version;
    return this;
  }

  /**
   * Version string (used by released event type)
   * @return version
   */
  
  @Schema(name = "version", description = "Version string (used by released event type)", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("version")
  public @Nullable String getVersion() {
    return version;
  }

  @JsonProperty("version")
  public void setVersion(@Nullable String version) {
    this.version = version;
  }

  public TeaCleEvent versions(List<@Valid TeaCleVersionSpecifier> versions) {
    this.versions = versions;
    return this;
  }

  public TeaCleEvent addVersionsItem(TeaCleVersionSpecifier versionsItem) {
    if (this.versions == null) {
      this.versions = new ArrayList<>();
    }
    this.versions.add(versionsItem);
    return this;
  }

  /**
   * List of version specifiers affected by this event
   * @return versions
   */
  @Valid 
  @Schema(name = "versions", description = "List of version specifiers affected by this event", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("versions")
  public List<@Valid TeaCleVersionSpecifier> getVersions() {
    return versions;
  }

  @JsonProperty("versions")
  public void setVersions(List<@Valid TeaCleVersionSpecifier> versions) {
    this.versions = versions;
  }

  public TeaCleEvent supportId(@Nullable String supportId) {
    this.supportId = supportId;
    return this;
  }

  /**
   * Reference to a support policy ID defined in the definitions section
   * @return supportId
   */
  
  @Schema(name = "supportId", description = "Reference to a support policy ID defined in the definitions section", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("supportId")
  public @Nullable String getSupportId() {
    return supportId;
  }

  @JsonProperty("supportId")
  public void setSupportId(@Nullable String supportId) {
    this.supportId = supportId;
  }

  public TeaCleEvent license(@Nullable String license) {
    this.license = license;
    return this;
  }

  /**
   * License identifier (used by released event type)
   * @return license
   */
  
  @Schema(name = "license", description = "License identifier (used by released event type)", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("license")
  public @Nullable String getLicense() {
    return license;
  }

  @JsonProperty("license")
  public void setLicense(@Nullable String license) {
    this.license = license;
  }

  public TeaCleEvent supersededByVersion(@Nullable String supersededByVersion) {
    this.supersededByVersion = supersededByVersion;
    return this;
  }

  /**
   * Version string that supersedes the affected versions (used by supersededBy event type)
   * @return supersededByVersion
   */
  
  @Schema(name = "supersededByVersion", description = "Version string that supersedes the affected versions (used by supersededBy event type)", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("supersededByVersion")
  public @Nullable String getSupersededByVersion() {
    return supersededByVersion;
  }

  @JsonProperty("supersededByVersion")
  public void setSupersededByVersion(@Nullable String supersededByVersion) {
    this.supersededByVersion = supersededByVersion;
  }

  public TeaCleEvent identifiers(List<@Valid TeaIdentifier> identifiers) {
    this.identifiers = identifiers;
    return this;
  }

  public TeaCleEvent addIdentifiersItem(TeaIdentifier identifiersItem) {
    if (this.identifiers == null) {
      this.identifiers = new ArrayList<>();
    }
    this.identifiers.add(identifiersItem);
    return this;
  }

  /**
   * New identifiers for the component (used by componentRenamed event type)
   * @return identifiers
   */
  @Valid 
  @Schema(name = "identifiers", description = "New identifiers for the component (used by componentRenamed event type)", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("identifiers")
  public List<@Valid TeaIdentifier> getIdentifiers() {
    return identifiers;
  }

  @JsonProperty("identifiers")
  public void setIdentifiers(List<@Valid TeaIdentifier> identifiers) {
    this.identifiers = identifiers;
  }

  public TeaCleEvent eventId(@Nullable Integer eventId) {
    this.eventId = eventId;
    return this;
  }

  /**
   * ID of the event being withdrawn (used by withdrawn event type)
   * @return eventId
   */
  
  @Schema(name = "eventId", description = "ID of the event being withdrawn (used by withdrawn event type)", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("eventId")
  public @Nullable Integer getEventId() {
    return eventId;
  }

  @JsonProperty("eventId")
  public void setEventId(@Nullable Integer eventId) {
    this.eventId = eventId;
  }

  public TeaCleEvent reason(@Nullable String reason) {
    this.reason = reason;
    return this;
  }

  /**
   * Human-readable explanation (used by withdrawn event type)
   * @return reason
   */
  
  @Schema(name = "reason", description = "Human-readable explanation (used by withdrawn event type)", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("reason")
  public @Nullable String getReason() {
    return reason;
  }

  @JsonProperty("reason")
  public void setReason(@Nullable String reason) {
    this.reason = reason;
  }

  public TeaCleEvent description(@Nullable String description) {
    this.description = description;
    return this;
  }

  /**
   * Human-readable description of the event
   * @return description
   */
  
  @Schema(name = "description", description = "Human-readable description of the event", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("description")
  public @Nullable String getDescription() {
    return description;
  }

  @JsonProperty("description")
  public void setDescription(@Nullable String description) {
    this.description = description;
  }

  public TeaCleEvent references(List<URI> references) {
    this.references = references;
    return this;
  }

  public TeaCleEvent addReferencesItem(URI referencesItem) {
    if (this.references == null) {
      this.references = new ArrayList<>();
    }
    this.references.add(referencesItem);
    return this;
  }

  /**
   * List of URLs to supporting documentation
   * @return references
   */
  @Valid 
  @Schema(name = "references", description = "List of URLs to supporting documentation", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("references")
  public List<URI> getReferences() {
    return references;
  }

  @JsonProperty("references")
  public void setReferences(List<URI> references) {
    this.references = references;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TeaCleEvent cleEvent = (TeaCleEvent) o;
    return Objects.equals(this.id, cleEvent.id) &&
        Objects.equals(this.type, cleEvent.type) &&
        Objects.equals(this.effective, cleEvent.effective) &&
        Objects.equals(this.published, cleEvent.published) &&
        Objects.equals(this.version, cleEvent.version) &&
        Objects.equals(this.versions, cleEvent.versions) &&
        Objects.equals(this.supportId, cleEvent.supportId) &&
        Objects.equals(this.license, cleEvent.license) &&
        Objects.equals(this.supersededByVersion, cleEvent.supersededByVersion) &&
        Objects.equals(this.identifiers, cleEvent.identifiers) &&
        Objects.equals(this.eventId, cleEvent.eventId) &&
        Objects.equals(this.reason, cleEvent.reason) &&
        Objects.equals(this.description, cleEvent.description) &&
        Objects.equals(this.references, cleEvent.references);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, type, effective, published, version, versions, supportId, license, supersededByVersion, identifiers, eventId, reason, description, references);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class TeaCleEvent {\n");
    sb.append("    id: ").append(toIndentedString(id)).append("\n");
    sb.append("    type: ").append(toIndentedString(type)).append("\n");
    sb.append("    effective: ").append(toIndentedString(effective)).append("\n");
    sb.append("    published: ").append(toIndentedString(published)).append("\n");
    sb.append("    version: ").append(toIndentedString(version)).append("\n");
    sb.append("    versions: ").append(toIndentedString(versions)).append("\n");
    sb.append("    supportId: ").append(toIndentedString(supportId)).append("\n");
    sb.append("    license: ").append(toIndentedString(license)).append("\n");
    sb.append("    supersededByVersion: ").append(toIndentedString(supersededByVersion)).append("\n");
    sb.append("    identifiers: ").append(toIndentedString(identifiers)).append("\n");
    sb.append("    eventId: ").append(toIndentedString(eventId)).append("\n");
    sb.append("    reason: ").append(toIndentedString(reason)).append("\n");
    sb.append("    description: ").append(toIndentedString(description)).append("\n");
    sb.append("    references: ").append(toIndentedString(references)).append("\n");
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

