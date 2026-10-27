/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.tea;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.function.Function;

import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;

/**
 * The mapper of the TEA surface (task TEA-6). The generated TEA models write every null and start
 * every list empty, while the 1.0.0 schemas forbid nulls where a field is typed string and want
 * optional lists absent; and every instant must be a UTC date-time with the Z designator. So this
 * mapper leaves nulls out and writes OffsetDateTime, ZonedDateTime and Instant as the instant's
 * text. Renderers set required lists (empty or not) and null the optional ones they leave out.
 * Nothing else in the application uses it.
 */
public final class TeaJson {

	public static final JsonMapper MAPPER = JsonMapper.builder()
			.changeDefaultPropertyInclusion(i -> JsonInclude.Value.construct(JsonInclude.Include.NON_NULL,
					JsonInclude.Include.NON_NULL))
			.disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
			.addModule(new SimpleModule("tea-utc-instants")
					.addSerializer(OffsetDateTime.class, new UtcInstant<>(OffsetDateTime.class, OffsetDateTime::toInstant))
					.addSerializer(ZonedDateTime.class, new UtcInstant<>(ZonedDateTime.class, ZonedDateTime::toInstant))
					.addSerializer(Instant.class, new UtcInstant<>(Instant.class, Function.identity())))
			.build();

	private TeaJson() {}

	/** Writes the instant's ISO text in UTC: {@code 2026-10-08T08:15:00Z}. */
	static final class UtcInstant<T> extends StdSerializer<T> {
		private final transient Function<T, Instant> toInstant;

		UtcInstant(Class<T> type, Function<T, Instant> toInstant) {
			super(type);
			this.toInstant = toInstant;
		}

		@Override
		public void serialize(T value, JsonGenerator gen, SerializationContext ctxt) {
			gen.writeString(toInstant.apply(value).toString());
		}
	}
}
