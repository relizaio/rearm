/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.common;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.cyclonedx.model.Component;

public enum CdxType {

	// this is based on cyclone dx 1.6
	// https://github.com/CycloneDX/specification/blob/master/schema/bom-1.6.schema.json
	
    APPLICATION(Set.of("APP")),
    FRAMEWORK(Set.of()),
    LIBRARY(Set.of()),
    CONTAINER(Set.of("DOCKER", "DOCKER_IMAGE")),
    PLATFORM(Set.of()),
    OPERATING_SYSTEM(Set.of("OS")),
    DEVICE(Set.of("HARDWARE", "HARDWARE_DEVICE")),
    DEVICE_DRIVER(Set.of()),
    FIRMWARE(Set.of()),
    FILE(Set.of("FILE_SYSTEM", "FILES", "DIRECTORIES", "DIRECTORY", "IMAGE", "FONT")),
    MACHINE_LEARNING_MODEL(Set.of()),
    DATA(Set.of()),
    CRYPTOGRAPHIC_ASSET(Set.of());
	
	private Set<String> synonyms = new HashSet<>();

	private CdxType(Set<String> synonyms) {
		this.synonyms = new HashSet<>(synonyms);
	}

	public static Component.Type toCycloneDxType (CdxType at) {
		return Component.Type.valueOf(at.name());
	}
	
	private static String cleanString (String typeStr) {
		return typeStr.toUpperCase().replaceAll(" ", "_").replaceAll("-", "_");
	}
	
	public static CdxType resolveStringToType (String typeStr) {
		CdxType retType = null;
		String cleanStr = cleanString(typeStr);
		retType = CdxType.valueOf(cleanStr);
		if (null == retType) {
			var optRetType = Arrays.asList(CdxType.values()).stream().filter(x -> x.synonyms.contains(cleanStr)).findAny();
			if (optRetType.isPresent()) retType = optRetType.get();
		}
		return retType;
	}
}
