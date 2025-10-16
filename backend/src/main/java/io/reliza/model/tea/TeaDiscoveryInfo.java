package io.reliza.model.tea;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import io.reliza.model.tea.TeaTeaServerInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
 * Discovery information for a TEI
 */

@Schema(name = "discovery-info", description = "Discovery information for a TEI")
@JsonTypeName("discovery-info")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2025-10-15T13:35:56.249199300-04:00[America/Toronto]", comments = "Generator version: 7.14.0")
public class TeaDiscoveryInfo {

  private UUID productReleaseUuid;

  @Valid
  private List<TeaTeaServerInfo> servers = new ArrayList<>();

  public TeaDiscoveryInfo() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public TeaDiscoveryInfo(UUID productReleaseUuid, List<TeaTeaServerInfo> servers) {
    this.productReleaseUuid = productReleaseUuid;
    this.servers = servers;
  }

  public TeaDiscoveryInfo productReleaseUuid(UUID productReleaseUuid) {
    this.productReleaseUuid = productReleaseUuid;
    return this;
  }

  /**
   * A UUID
   * @return productReleaseUuid
   */
  @NotNull @Valid 
  @Schema(name = "productReleaseUuid", description = "A UUID", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("productReleaseUuid")
  public UUID getProductReleaseUuid() {
    return productReleaseUuid;
  }

  public void setProductReleaseUuid(UUID productReleaseUuid) {
    this.productReleaseUuid = productReleaseUuid;
  }

  public TeaDiscoveryInfo servers(List<TeaTeaServerInfo> servers) {
    this.servers = servers;
    return this;
  }

  public TeaDiscoveryInfo addServersItem(TeaTeaServerInfo serversItem) {
    if (this.servers == null) {
      this.servers = new ArrayList<>();
    }
    this.servers.add(serversItem);
    return this;
  }

  /**
   * Array of TEA server information
   * @return servers
   */
  @NotNull @Valid @Size(min = 1) 
  @Schema(name = "servers", description = "Array of TEA server information", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("servers")
  public List<TeaTeaServerInfo> getServers() {
    return servers;
  }

  public void setServers(List<TeaTeaServerInfo> servers) {
    this.servers = servers;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TeaDiscoveryInfo discoveryInfo = (TeaDiscoveryInfo) o;
    return Objects.equals(this.productReleaseUuid, discoveryInfo.productReleaseUuid) &&
        Objects.equals(this.servers, discoveryInfo.servers);
  }

  @Override
  public int hashCode() {
    return Objects.hash(productReleaseUuid, servers);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class TeaDiscoveryInfo {\n");
    sb.append("    productReleaseUuid: ").append(toIndentedString(productReleaseUuid)).append("\n");
    sb.append("    servers: ").append(toIndentedString(servers)).append("\n");
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

