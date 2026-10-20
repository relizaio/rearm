/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import org.apache.commons.lang3.StringUtils;

import io.reliza.common.CommonVariables;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ComponentData;
import io.reliza.model.DeliverableData;
import io.reliza.model.DeliverableData.BelongsToOrganization;
import io.reliza.model.OrganizationData;

/**
 * The supplier ReARM names for a node it writes into an SBOM (SCORE-30 ADR-2, ADR-12, SCORE-39
 * ADR-3): the name a producer stated for a deliverable (its publisher) when there is one; else the
 * supplier recorded on the component; otherwise the organization, but only for its own software. A
 * component or deliverable the organization records as EXTERNAL is third-party software, and the
 * external sentinel organization is no vendor, so for them ReARM names none rather than claim to be
 * their supplier, unless the component names one.
 */
public final class SbomSupplierNames {

	/** The longest supplier name accepted on a component, after trimming (SCORE-39 ADR-1). */
	public static final int MAX_LENGTH = 255;

	private SbomSupplierNames() {}

	/**
	 * A supplier name as a component stores it (SCORE-39 ADR-1): trimmed, null when blank. Refused,
	 * not sanitised, when longer than {@link #MAX_LENGTH} or when it carries {@code <}, {@code >},
	 * a control character, an invisible formatting character (bidi controls, zero-width characters),
	 * a line or paragraph separator or a lone surrogate: a name has no markup to keep, escaping
	 * would put entities into the SBOM, and an invisible character can make it read as another
	 * vendor's name.
	 *
	 * @throws RelizaException {@code "supplierName invalid: <reason>"}
	 */
	public static String validName(String value) throws RelizaException {
		String trimmed = value.strip();
		if (trimmed.isEmpty()) return null;
		if (trimmed.length() > MAX_LENGTH) {
			throw new RelizaException("supplierName invalid: at most " + MAX_LENGTH + " characters");
		}
		// over code points, so a character outside the BMP (the tag characters U+E0001 and
		// U+E0020..U+E007F among them) is classified too; a lone surrogate is one SURROGATE code point
		boolean refused = trimmed.codePoints().anyMatch(cp -> {
			int type = Character.getType(cp);
			return '<' == cp || '>' == cp || Character.isISOControl(cp) || Character.FORMAT == type
					|| Character.LINE_SEPARATOR == type || Character.PARAGRAPH_SEPARATOR == type
					|| Character.SURROGATE == type;
		});
		if (refused) {
			throw new RelizaException("supplierName invalid: must not contain HTML or control characters");
		}
		return trimmed;
	}

	/**
	 * The supplier of the node that describes a release of {@code cd} (the merged release node and
	 * the product placeholder): the component's supplier name when it records one, whatever its
	 * classification and organization; else the organization's name when the component is its own;
	 * else null.
	 */
	public static String forRelease(OrganizationData od, ComponentData cd) {
		String componentSupplier = componentSupplier(cd);
		if (null != componentSupplier) return componentSupplier;
		return organizationFor(od, cd);
	}

	/**
	 * The supplier of the root of an SBOM uploaded to a release of {@code cd}: through
	 * {@link #forDeliverable} when it is attached to {@code dd}, else as {@link #forRelease}.
	 */
	public static String forUpload(OrganizationData od, ComponentData cd, DeliverableData dd) {
		if (null == dd) return forRelease(od, cd);
		return forDeliverable(od, cd, dd.getPublisher(), dd.getIsInternal());
	}

	/**
	 * The supplier of the root of an SBOM attached to a deliverable with {@code publisher} and
	 * classification {@code deliverableClass} (null on legacy rows, read as INTERNAL): the publisher
	 * when it is not blank, whatever the classifications; else the component's supplier name when it
	 * records one; else null for an EXTERNAL deliverable; else the organization as
	 * {@link #forRelease} names it.
	 */
	public static String forDeliverable(OrganizationData od, ComponentData cd, String publisher,
			BelongsToOrganization deliverableClass) {
		if (StringUtils.isNotBlank(publisher)) return publisher.trim();
		String componentSupplier = componentSupplier(cd);
		if (null != componentSupplier) return componentSupplier;
		if (deliverableClass == BelongsToOrganization.EXTERNAL) return null;
		return organizationFor(od, cd);
	}

	private static String componentSupplier(ComponentData cd) {
		return null == cd ? null : StringUtils.trimToNull(cd.getSupplierName());
	}

	private static String organizationFor(OrganizationData od, ComponentData cd) {
		if (null == od || null == cd || CommonVariables.EXTERNAL_PROJ_ORG_UUID.equals(od.getUuid())) return null;
		if (cd.getIsInternalOrDefault() == BelongsToOrganization.EXTERNAL) return null;
		return StringUtils.trimToNull(od.getName());
	}
}
