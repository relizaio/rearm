/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.cdx.v20;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The definition in the pinned CycloneDX 2.0 schema snapshot that a subset type (or, on a field,
 * that one property) binds to, as a JSON pointer such as
 * {@code #/$defs/cyclonedx-blueprint-2.0/$defs/asset}. {@code Cdx20SchemaConformanceTest} walks the
 * subset from {@link Cdx20Document} and checks every bound property against the snapshot, so a
 * snapshot bump lists the fields that moved or were renamed.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD})
public @interface Cdx20SchemaDef {
	String value();
}
