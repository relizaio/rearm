/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories.dao;

public interface VulnViolationsChartDao {
	String getDateKey();
	Integer getCritical();
	Integer getHigh();
	Integer getMedium();
	Integer getLow();
	Integer getUnassigned();
	/** Null on rows seeded before the kevCount key existed -- the read side
	 * omits the KEV point rather than drawing a false zero. */
	Integer getKevCount();
	Integer getLicenseViolations();
	Integer getOperationalViolations();
	Integer getSecurityViolations();
}
