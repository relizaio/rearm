/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

/**
 * The manufacturer's ATTESTED claim about what a component's upstream maintainer is
 * actually doing -- FDA's "software level of support provided through monitoring and
 * maintenance from the software component manufacturer" (V.A.4(b), L1002-1015).
 *
 * <p>Separate from {@link SupportStatus}, which is derived from dates, and never
 * reconciled with it. An earlier revision typed this field as {@code SupportStatus},
 * which could express only one of FDA's three terms: "actively supported" is not
 * "actively maintained" (a vendor can patch a fork nobody maintains, and an upstream
 * can be maintained but commercially unsupported), and "no longer maintained" had no
 * representation at all. It also allowed a lifecycle state to be attested as a level,
 * so a component could claim {@code levelOfSupport = END_OF_SUPPORT} -- answering
 * "what support does upstream provide?" with a date computation the reviewer already
 * had.
 *
 * <p><b>{@link #getWireValue()}, not {@code name()}, is what goes on the wire.</b> The
 * exported value is FDA's phrase verbatim, lowercase. CycloneDX taxonomy PR #186
 * (proposing {@code cdx:fda:level-of-support}) specifies free text with the guidance's
 * examples "used verbatim when applicable", so emitting {@code ACTIVELY_MAINTAINED}
 * would miss an equality check that {@code actively maintained} passes -- and adopting
 * that key later would become a simultaneous key AND value change with no safe
 * dual-emit window. Keeping the constant separate from its wire string also means a
 * guidance revision is a one-line change here rather than a stored-data migration.
 *
 * <p><b>There is deliberately no fourth "unknown" member.</b> FDA gives three words;
 * #186 has three; CLE has no such vocabulary at all. "Assessed, but the maintenance
 * status could not be determined" is a NULL level plus a justification -- which is
 * exactly the L1021-1022 mechanism for being unable to provide the information, and
 * is a stronger record than an undefined token. {@link SupportParty} already
 * establishes the house rule that nullable means unknown.
 *
 * <p>{@link #NO_LONGER_MAINTAINED} and {@link #ABANDONED} are negative claims about a
 * named third party's business. Both REQUIRE a justification at write time, and the
 * boundary between them is not defined by FDA -- an organisation must define it once,
 * in its 7f narrative, and apply it consistently. ReARM ships no default definition
 * for either: under section 3 it may state what it knows about its own records, not
 * characterise someone else's project on the manufacturer's behalf.
 */
public enum LevelOfSupport {

	ACTIVELY_MAINTAINED("actively maintained"),
	NO_LONGER_MAINTAINED("no longer maintained"),
	ABANDONED("abandoned");

	private final String wireValue;

	LevelOfSupport(String wireValue) {
		this.wireValue = wireValue;
	}

	/** FDA's phrase, verbatim and lowercase. Emit this, never {@link #name()}. */
	public String getWireValue() {
		return wireValue;
	}

	/** True for claims that require a stated basis before they may be recorded. */
	public boolean requiresJustification() {
		return this != ACTIVELY_MAINTAINED;
	}
}
