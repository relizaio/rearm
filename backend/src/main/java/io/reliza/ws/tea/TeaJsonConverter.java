/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.tea;

import java.util.List;

import org.springframework.core.ResolvableType;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;

/**
 * Writes the generated TEA models, and lists of them, with {@link TeaJson#MAPPER} (task TEA-6).
 * It reads nothing and writes nothing else, so the rest of the application keeps Spring's mapper.
 */
public class TeaJsonConverter extends JacksonJsonHttpMessageConverter {

	static final String TEA_MODEL_PACKAGE = "io.reliza.model.tea";

	public TeaJsonConverter() {
		super(TeaJson.MAPPER);
	}

	/** A class of the generated package (not its subpackages), or a list of one. */
	static boolean isTeaModel(ResolvableType type, Class<?> valueClass) {
		if (null != valueClass && TEA_MODEL_PACKAGE.equals(valueClass.getPackageName())) return true;
		Class<?> raw = null != valueClass ? valueClass : (null == type ? null : type.resolve());
		if (null == raw || !List.class.isAssignableFrom(raw) || null == type) return false;
		ResolvableType asList = type.as(List.class);
		Class<?> element = asList.getGeneric(0).resolve();
		return null != element && TEA_MODEL_PACKAGE.equals(element.getPackageName());
	}

	@Override
	public boolean canWrite(ResolvableType type, Class<?> valueClass, MediaType mediaType) {
		return isTeaModel(type, valueClass) && super.canWrite(type, valueClass, mediaType);
	}

	@Override
	public boolean canWrite(Class<?> clazz, MediaType mediaType) {
		return isTeaModel(ResolvableType.forClass(clazz), clazz) && super.canWrite(clazz, mediaType);
	}

	@Override
	public boolean canRead(ResolvableType type, MediaType mediaType) {
		return false;
	}

	@Override
	public boolean canRead(Class<?> clazz, MediaType mediaType) {
		return false;
	}
}
