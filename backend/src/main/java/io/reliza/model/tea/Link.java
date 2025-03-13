/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.tea;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Link {
  public enum ContentEnum {
		OCI,
		PLAIN_JSON,
		OCTET_STREAM,
		PLAIN_XML
  }

	  
  @JsonProperty(CommonVariables.URI_FIELD)
  private String uri;

  @JsonProperty(CommonVariables.CONTENT_FIELD)
  private ContentEnum content;

}
