/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import org.apache.commons.lang3.StringUtils;

import io.reliza.common.LicenseUrls;
import io.reliza.common.SpdxLicenseExpressions;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ComponentData;
import io.reliza.model.OrganizationData;
import io.reliza.model.ReleaseData;

/**
 * The licence ReARM declares for a node it writes into an SBOM (SCORE-39 ADR-1, ADR-3): the
 * release's override, else the component's licence, else the organization's default. Blank counts
 * as unset at every level, and the URL comes only from the level whose expression won (a release
 * has none). Declared data, not a vendor claim: an EXTERNAL component and the external sentinel
 * organization resolve like any other.
 */
public final class DeclaredLicenses {

	/** The level a declared licence was resolved from. */
	public enum DeclaredLicenseLevel {
		ORGANIZATION,
		COMPONENT,
		RELEASE
	}

	/** A resolved declared licence: a canonical SPDX expression, its URL or null, and its level. */
	public record DeclaredLicense(String expression, String url, DeclaredLicenseLevel level) {}

	/** The expression and URL a level stores after a patch is applied. */
	public record LicensePatch(String expression, String url) {}

	private DeclaredLicenses() {}

	/**
	 * The licence declared for a release {@code rd} of component {@code cd} in organization
	 * {@code od}, or null when no level declares one. Null arguments are skipped.
	 */
	public static DeclaredLicense forRelease(OrganizationData od, ComponentData cd, ReleaseData rd) {
		if (null != rd && StringUtils.isNotBlank(rd.getLicenseExpression())) {
			return new DeclaredLicense(rd.getLicenseExpression().strip(), null, DeclaredLicenseLevel.RELEASE);
		}
		if (null != cd && StringUtils.isNotBlank(cd.getLicenseExpression())) {
			return new DeclaredLicense(cd.getLicenseExpression().strip(), StringUtils.trimToNull(cd.getLicenseUrl()),
					DeclaredLicenseLevel.COMPONENT);
		}
		OrganizationData.Settings settings = null == od ? null : od.getSettings();
		if (null != settings && StringUtils.isNotBlank(settings.getDefaultLicenseExpression())) {
			return new DeclaredLicense(settings.getDefaultLicenseExpression().strip(),
					StringUtils.trimToNull(settings.getDefaultLicenseUrl()), DeclaredLicenseLevel.ORGANIZATION);
		}
		return null;
	}

	/**
	 * {@code value} as {@link SpdxLicenseExpressions#canonical} writes it, for a non-blank value.
	 *
	 * @throws RelizaException {@code "<field> invalid: <reason>"} when the validator refuses it
	 */
	public static String canonicalExpression(String value, String field) throws RelizaException {
		try {
			return SpdxLicenseExpressions.canonical(value);
		} catch (IllegalArgumentException e) {
			throw new RelizaException(field + " invalid: " + e.getMessage());
		}
	}

	/**
	 * What a level (organization or component) stores after a PATCH of its expression and URL
	 * (ADR-1): a null patch value leaves the stored value alone, a blank one clears it, anything else
	 * is validated and stored in canonical form. Clearing the expression clears the URL with it, and
	 * a URL is accepted only when the level stores an expression after the patch.
	 *
	 * @throws RelizaException naming the field, before anything is stored
	 */
	public static LicensePatch patch(String storedExpression, String storedUrl, String expressionPatch,
			String urlPatch, String expressionField, String urlField) throws RelizaException {
		String expression = storedExpression;
		String url = storedUrl;
		if (null != expressionPatch) {
			if (expressionPatch.isBlank()) {
				expression = null;
				url = null;
			} else {
				expression = canonicalExpression(expressionPatch, expressionField);
			}
		}
		if (null != urlPatch) {
			if (urlPatch.isBlank()) {
				url = null;
			} else {
				try {
					url = LicenseUrls.canonical(urlPatch);
				} catch (IllegalArgumentException e) {
					throw new RelizaException(urlField + " invalid: " + e.getMessage());
				}
			}
		}
		if (null != url && null == expression) {
			throw new RelizaException(urlField + " needs a " + expressionField + " at the same level");
		}
		return new LicensePatch(expression, url);
	}
}
