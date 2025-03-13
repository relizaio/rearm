/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import lombok.Data;
import java.util.List;
import java.util.UUID;


@Data
public class AddDeliverablesDto {
    @JsonProperty(CommonVariables.RELEASE_FIELD)
    private UUID release;

    @JsonProperty(CommonVariables.COMPONENT_FIELD)
    private UUID component;

    @JsonProperty(CommonVariables.VERSION_FIELD)
    private String version;

    @JsonProperty(CommonVariables.ARTIFACTS_FIELD)
    private List<DeliverableDto> deliverables;
}
