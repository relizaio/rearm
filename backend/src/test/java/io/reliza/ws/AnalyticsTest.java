/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;


import java.time.ZonedDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.model.ComponentData.ComponentType;
import io.reliza.service.AnalyticsMetricsService;

/**
 * Unit test related to Analytics queries functionality
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
public class AnalyticsTest 
{
	@Autowired
    private AnalyticsMetricsService analyticsMetricsService;
	
	private static final Logger log = LoggerFactory.getLogger(AnalyticsTest.class);
	
	@Test
	public void retrieveComponentsWithMostReleases() {
		var crs = analyticsMetricsService.analyticsComponentsWithMostRecentReleases(
				ZonedDateTime.parse("2024-12-01T10:15:30-05:00"), ComponentType.COMPONENT, 5, new UUID(0,0));
		crs.forEach(cr -> log.info(cr.componentname()));
		log.info("done");
	}
	
	@Test
	public void retrieveBranchesWithMostReleases() {
		var crs = analyticsMetricsService.analyticsBranchesWithMostRecentReleases(
				ZonedDateTime.parse("2024-12-01T10:15:30-05:00"), ComponentType.COMPONENT, 5, new UUID(0,0));
		crs.forEach(cr -> log.info(cr.componentname()));
		log.info("done");
	}
	
	
}
