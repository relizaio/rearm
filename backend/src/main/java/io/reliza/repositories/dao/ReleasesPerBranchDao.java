/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories.dao;

import java.util.UUID;

public interface ReleasesPerBranchDao {
	UUID getComponentuuid();
	String getComponentname();
	UUID getBranchuuid();
	String getBranchname();
	String getComponenttype();
	Long getRlzcount();
}
