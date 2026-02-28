/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.common;

import java.util.LinkedHashSet;
import java.util.Set;

import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public class EnvironmentType {
	
	private String environmentType;
	
	private EnvironmentType(String environmentType) {
		// clean input of any possible injections
		this.environmentType = Jsoup.clean(environmentType, Safelist.basic());
	}
	
	public enum EnvironmentTypeEnum {
		// it's not required to use all of those
		DEV,
		BUILD, // i.e., jenkins server would go there
		TEST,
		SIT, // system integration testing
		UAT, // user acceptance testing
		PAT, // performance acceptance testing
		STAGING,
		PRODUCTION
		;
		
		private EnvironmentTypeEnum () {}
		
		@Override
		public String toString() {
			return this.name();
		}
	}
	
    @JsonCreator
    public static EnvironmentType forValue (String value) {
    	return new EnvironmentType(value);
    }
    
    public static EnvironmentType fromEnum (EnvironmentTypeEnum ete) {
    	return forValue(ete.toString());
    }
    
	@JsonValue
	@Override
	public String toString() {
		return this.environmentType;
	}
	
	@Override
    public int hashCode() {
        return this.environmentType.hashCode();
    }
	
    @Override
    public boolean equals(Object o) {
    	if (!(o instanceof EnvironmentType)) return false;
        if (this == o) return true;
        return this.toString().equals(o.toString());
    }
	
	public static Set<EnvironmentType> getBuiltinValues () {
		Set<EnvironmentType> builtInSet = new LinkedHashSet<>();
		for (EnvironmentTypeEnum ete : EnvironmentTypeEnum.values()) {
			builtInSet.add(new EnvironmentType(ete.toString()));
		}
		return builtInSet;
	}
}
