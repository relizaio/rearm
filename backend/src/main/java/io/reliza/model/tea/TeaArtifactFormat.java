package io.reliza.model.tea;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import io.reliza.model.tea.TeaChecksum;
import java.net.URI;
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
 * A security-related document in a specific format
 */

@Schema(name = "artifact-format", description = "A security-related document in a specific format")
@JsonTypeName("artifact-format")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2026-10-08T02:50:49.497696677Z[Etc/UTC]", comments = "Generator version: 7.21.0")
public class TeaArtifactFormat {

  private String mediaType;

  private @Nullable String description;

  private @Nullable URI url;

  private @Nullable URI signatureUrl;

  @Valid
  private List<@Valid TeaChecksum> checksums = new ArrayList<>();

  public TeaArtifactFormat() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public TeaArtifactFormat(String mediaType) {
    this.mediaType = mediaType;
  }

  public TeaArtifactFormat mediaType(String mediaType) {
    this.mediaType = mediaType;
    return this;
  }

  /**
   * The Media Type of the document.  A media type shall appear at most once across the `formats` of a single TEA Artifact revision, so that every format can be selected unambiguously by media type when downloading its content or its signature. 
   * @return mediaType
   */
  @NotNull 
  @Schema(name = "mediaType", description = "The Media Type of the document.  A media type shall appear at most once across the `formats` of a single TEA Artifact revision, so that every format can be selected unambiguously by media type when downloading its content or its signature. ", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("mediaType")
  public String getMediaType() {
    return mediaType;
  }

  @JsonProperty("mediaType")
  public void setMediaType(String mediaType) {
    this.mediaType = mediaType;
  }

  public TeaArtifactFormat description(@Nullable String description) {
    this.description = description;
    return this;
  }

  /**
   * A free text describing the TEA Artifact
   * @return description
   */
  
  @Schema(name = "description", description = "A free text describing the TEA Artifact", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("description")
  public @Nullable String getDescription() {
    return description;
  }

  @JsonProperty("description")
  public void setDescription(@Nullable String description) {
    this.description = description;
  }

  public TeaArtifactFormat url(@Nullable URI url) {
    this.url = url;
    return this;
  }

  /**
   * External download URL for this format of the TEA Artifact.  This is always a location outside the TEA API. When present, clients shall retrieve the content from it. When absent, the TEA server hosts the content itself and clients shall retrieve it from the artifact download endpoint, `/artifact/{uuid}/{artifactVersion}/download`, selecting this format by its `mediaType`.  `url` and `signatureUrl` should be stable URLs. Servers should not publish pre-signed or otherwise expiring URLs in them. A server that serves content from expiring storage links shall omit them and answer from the download endpoints, with `302` where the bytes live elsewhere.  A TEA access token is sent only to the TEA server's own API base URL, never to an external URL. External locations are either openly accessible or require credentials the client arranges separately.  External `url` values are part of the published artifact revision. Changing `url` shall create a new artifact revision, even when content bytes and checksums are unchanged. The server shall preserve the URLs published on each historical artifact revision. If a Collection adopts that new revision, collection versioning follows `collection.version`.  Byte stability for content retrieved through the server's download endpoint follows `artifact.version`.  The TEA server does not control the bytes behind an external `url`, so for every format that has a `url` the server shall publish at least one checksum: the checksums are the integrity statement for the revision. Content retrieved from `url` that does not match them is not the content of that revision. 
   * @return url
   */
  @Valid 
  @Schema(name = "url", description = "External download URL for this format of the TEA Artifact.  This is always a location outside the TEA API. When present, clients shall retrieve the content from it. When absent, the TEA server hosts the content itself and clients shall retrieve it from the artifact download endpoint, `/artifact/{uuid}/{artifactVersion}/download`, selecting this format by its `mediaType`.  `url` and `signatureUrl` should be stable URLs. Servers should not publish pre-signed or otherwise expiring URLs in them. A server that serves content from expiring storage links shall omit them and answer from the download endpoints, with `302` where the bytes live elsewhere.  A TEA access token is sent only to the TEA server's own API base URL, never to an external URL. External locations are either openly accessible or require credentials the client arranges separately.  External `url` values are part of the published artifact revision. Changing `url` shall create a new artifact revision, even when content bytes and checksums are unchanged. The server shall preserve the URLs published on each historical artifact revision. If a Collection adopts that new revision, collection versioning follows `collection.version`.  Byte stability for content retrieved through the server's download endpoint follows `artifact.version`.  The TEA server does not control the bytes behind an external `url`, so for every format that has a `url` the server shall publish at least one checksum: the checksums are the integrity statement for the revision. Content retrieved from `url` that does not match them is not the content of that revision. ", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("url")
  public @Nullable URI getUrl() {
    return url;
  }

  @JsonProperty("url")
  public void setUrl(@Nullable URI url) {
    this.url = url;
  }

  public TeaArtifactFormat signatureUrl(@Nullable URI signatureUrl) {
    this.signatureUrl = signatureUrl;
    return this;
  }

  /**
   * External download URL for a detached signature of this format of the TEA Artifact.  As with `url`, this is always a location outside the TEA API. When present, clients shall retrieve the signature from it. When absent, clients shall retrieve it from the artifact signature download endpoint, `/artifact/{uuid}/{artifactVersion}/signature/download`, selecting this format by its `mediaType`; that endpoint returns `404` when no signature is published for the format. The same access-token, stable-URL, and revision rules as for `url` apply to `signatureUrl`. Byte stability for signature retrieval through the server's signature download endpoint follows `artifact.version`.  This specification does not define which signature technology is used, nor model the signing algorithm, key, or certificate chain. 
   * @return signatureUrl
   */
  @Valid 
  @Schema(name = "signatureUrl", description = "External download URL for a detached signature of this format of the TEA Artifact.  As with `url`, this is always a location outside the TEA API. When present, clients shall retrieve the signature from it. When absent, clients shall retrieve it from the artifact signature download endpoint, `/artifact/{uuid}/{artifactVersion}/signature/download`, selecting this format by its `mediaType`; that endpoint returns `404` when no signature is published for the format. The same access-token, stable-URL, and revision rules as for `url` apply to `signatureUrl`. Byte stability for signature retrieval through the server's signature download endpoint follows `artifact.version`.  This specification does not define which signature technology is used, nor model the signing algorithm, key, or certificate chain. ", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("signatureUrl")
  public @Nullable URI getSignatureUrl() {
    return signatureUrl;
  }

  @JsonProperty("signatureUrl")
  public void setSignatureUrl(@Nullable URI signatureUrl) {
    this.signatureUrl = signatureUrl;
  }

  public TeaArtifactFormat checksums(List<@Valid TeaChecksum> checksums) {
    this.checksums = checksums;
    return this;
  }

  public TeaArtifactFormat addChecksumsItem(TeaChecksum checksumsItem) {
    if (this.checksums == null) {
      this.checksums = new ArrayList<>();
    }
    this.checksums.add(checksumsItem);
    return this;
  }

  /**
   * Checksums for this artifact format's content bytes (the payload retrieved from `url` or from the artifact download endpoint for this `mediaType`), not the detached signature. When present, `checksums` shall contain at least one entry. When `url` is present, `checksums` is required. Those checksums are the integrity statement for that external `url`. Content retrieved from `url` that does not match them is not the content of that revision.  Published checksums shall be calculated over the artifact format's bytes before HTTP content coding is applied. Clients verifying them shall first remove any HTTP transfer coding and content coding, and shall not otherwise transform or canonicalize the artifact bytes. An artifact that is itself compressed, such as a `.gz` file, stays in that form. The TEA servers shall not declare the artifact's own compression as HTTP content coding, because clients would then remove it and the checksum would not match. 
   * @return checksums
   */
  @Valid @Size(min = 1) 
  @Schema(name = "checksums", description = "Checksums for this artifact format's content bytes (the payload retrieved from `url` or from the artifact download endpoint for this `mediaType`), not the detached signature. When present, `checksums` shall contain at least one entry. When `url` is present, `checksums` is required. Those checksums are the integrity statement for that external `url`. Content retrieved from `url` that does not match them is not the content of that revision.  Published checksums shall be calculated over the artifact format's bytes before HTTP content coding is applied. Clients verifying them shall first remove any HTTP transfer coding and content coding, and shall not otherwise transform or canonicalize the artifact bytes. An artifact that is itself compressed, such as a `.gz` file, stays in that form. The TEA servers shall not declare the artifact's own compression as HTTP content coding, because clients would then remove it and the checksum would not match. ", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("checksums")
  public List<@Valid TeaChecksum> getChecksums() {
    return checksums;
  }

  @JsonProperty("checksums")
  public void setChecksums(List<@Valid TeaChecksum> checksums) {
    this.checksums = checksums;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TeaArtifactFormat artifactFormat = (TeaArtifactFormat) o;
    return Objects.equals(this.mediaType, artifactFormat.mediaType) &&
        Objects.equals(this.description, artifactFormat.description) &&
        Objects.equals(this.url, artifactFormat.url) &&
        Objects.equals(this.signatureUrl, artifactFormat.signatureUrl) &&
        Objects.equals(this.checksums, artifactFormat.checksums);
  }

  @Override
  public int hashCode() {
    return Objects.hash(mediaType, description, url, signatureUrl, checksums);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class TeaArtifactFormat {\n");
    sb.append("    mediaType: ").append(toIndentedString(mediaType)).append("\n");
    sb.append("    description: ").append(toIndentedString(description)).append("\n");
    sb.append("    url: ").append(toIndentedString(url)).append("\n");
    sb.append("    signatureUrl: ").append(toIndentedString(signatureUrl)).append("\n");
    sb.append("    checksums: ").append(toIndentedString(checksums)).append("\n");
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

