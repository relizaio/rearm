/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.common;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public class VcsType {
	
	public static final VcsType GIT = new VcsType("Git");
	public static final VcsType SUBVERSION = new VcsType("Subversion");
	public static final VcsType MERCURIAL = new VcsType("Mercurial");
	
	private static final Map<String, VcsType> vcsTypeMap = new HashMap<>();
	
	static {
		vcsTypeMap.put(GIT.toString(), GIT);
		vcsTypeMap.put(SUBVERSION.toString(), SUBVERSION);
		vcsTypeMap.put(MERCURIAL.toString(), MERCURIAL);
	}
	
	public static VcsType resolveStringToType (String typeStr) {
		return vcsTypeMap.get(typeStr);
	}

	/**
	 * Jackson deserialization entry point. Under Jackson 2 the private
	 * {@code VcsType(String)} ctor was picked up implicitly, so any input
	 * (e.g. CLI sending {@code "git"} lower-case) round-tripped to a
	 * {@code VcsType{name=<as-supplied>}}. Jackson 3 needs an explicit
	 * @JsonCreator; pinning it on the case-sensitive map lookup
	 * regressed lower-case inputs to null. Match the old shape: prefer
	 * the canonical static instance when the value matches one of the
	 * known names (case-insensitive) so reference-equality keeps working
	 * for built-ins, otherwise mint a fresh instance.
	 */
	@JsonCreator
	public static VcsType forValue (String typeStr) {
		if (typeStr == null) return null;
		for (Entry<String, VcsType> e : vcsTypeMap.entrySet()) {
			if (e.getKey().equalsIgnoreCase(typeStr)) return e.getValue();
		}
		return new VcsType(typeStr);
	}
	
	// TODO: later make this data driven - extensible via specific db table
	private String name;
	
	private VcsType (String name) {
		this.name = name;
	}
	
	public static Set<String> getAvailableTypes() {
		return vcsTypeMap.entrySet().stream().map(Entry::getKey).collect(Collectors.toSet());
	}
	
	@JsonValue
	@Override
	public String toString() {
		return this.name;
	}
	
	@Override
	public int hashCode() {
		return this.name.hashCode();
	}

}
