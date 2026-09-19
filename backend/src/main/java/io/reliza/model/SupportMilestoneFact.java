/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One milestone date inside {@link SupportData#milestones()}, with its own
 * provenance. Replaces the {@code sbom_component_support_milestones} row that
 * V901 created: same facts, but carried in the parent attestation's JSONB so a
 * component with no dates at all is still representable (V901's NOT NULL date
 * made "assessed, nothing published" impossible to store).
 *
 * <p>Dates and timestamps are STRINGS -- ISO-8601 local date, and RFC-3339 UTC
 * instants -- following {@link SbomComponentFlowControl}.
 *
 * <p><b>Not because the mapper cannot handle temporal types.</b> An earlier revision
 * of this comment said the JSONB mapper "is not configured with the time module".
 * That is false: {@code jackson-datatype-jsr310} is on the classpath and
 * hypersistence's {@code ObjectMapperWrapper} calls {@code findAndRegisterModules()},
 * so temporal types DO bind. The real reason is worse and is why the text form is
 * load-bearing: that mapper is raw, so {@code WRITE_DATES_AS_TIMESTAMPS} is on, and a
 * {@code LocalDate} field would be written as {@code [2026,8,31]} and an instant as an
 * epoch decimal. Those are unqueryable by the {@code ->>'date'} idiom the repository
 * already uses, unsortable, and unreadable to the auditor this record exists for.
 * Worse, the on-disk shape would then depend on the classpath and on global mapper
 * configuration that this feature does not control and cannot pin.
 *
 * <p>So: do NOT "fix" this by registering a module and retyping the field. Doing so
 * writes {@code [2026,8,31]} into a column whose existing rows hold {@code "2026-08-31"},
 * silently, because both are valid JSONB. The stored form is deliberately independent
 * of Jackson configuration.
 *
 * <p>{@code lastAssessed} is CALLER-SUPPLIED: when the human actually checked,
 * not when the row was written. Those differ whenever someone records earlier
 * work ("I read the vendor advisory on 12 August, filed it on 30 August"), and
 * ALCOA Contemporaneous cannot be satisfied if they are forced equal. The
 * system's own record time lives on the audit row's {@code asserted_date}.
 *
 * <p>{@code notes} is INTERNAL ONLY and must never reach an export.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SupportMilestoneFact(
		String date,
		SupportSource source,
		String lastAssessed,
		UUID assertedBy,
		String notes) implements Serializable {


	/**
	 * The milestone date as a temporal value, or null when unparseable.
	 *
	 * <p>An absent date is expressed by OMITTING the key, never by a JSON null: the storage
	 * constraint rejects a null-valued date, because a null that reaches here is
	 * indistinguishable on export from a date that failed to parse.
	 */
	public LocalDate dateValue() {
		if (null == date || date.isBlank()) return null;
		try {
			return LocalDate.parse(date);
		} catch (DateTimeParseException e) {
			return null;
		}
	}
}
