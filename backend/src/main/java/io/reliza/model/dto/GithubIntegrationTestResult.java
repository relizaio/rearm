/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Result of a GitHub App "Test connection" probe (testGithubIntegration mutation).
 * {@code ok} is the overall verdict, {@code message} is a human-readable
 * explanation suitable for a UI banner, and {@code installations} is the number
 * of repositories/orgs the App is installed on (0 when not installed or on error).
 */
@Data
@AllArgsConstructor
public class GithubIntegrationTestResult {
	private boolean ok;
	private String message;
	private int installations;
}
