/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import io.reliza.model.SafetyContextProposal;

public interface SafetyContextProposalRepository extends JpaRepository<SafetyContextProposal, UUID> {
}
