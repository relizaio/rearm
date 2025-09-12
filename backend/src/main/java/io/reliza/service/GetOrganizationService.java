/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.Optional;
import java.util.UUID;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import io.reliza.model.Organization;
import io.reliza.model.OrganizationData;
import io.reliza.repositories.OrganizationRepository;

@Service
public class GetOrganizationService {

	private final OrganizationRepository repository;
	
	GetOrganizationService(OrganizationRepository repository) {
	    this.repository = repository;
	}
	
	public Optional<Organization> getOrganization (@NonNull UUID uuid) {
		return repository.findById(uuid);
	}
	
	public Optional<OrganizationData> getOrganizationData (@NonNull UUID uuid) {
		Optional<OrganizationData> orgData = Optional.empty();
		Optional<Organization> org = getOrganization(uuid);
		if (org.isPresent()) {
			orgData = Optional
							.of(
								OrganizationData
									.orgDataFromDbRecord(org
										.get()
								));
		}
		return orgData;
	}
}