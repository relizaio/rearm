/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.common;

import java.util.LinkedList;
import java.util.List;

import lombok.Data;

@Data
public class ValidationResult {
	private List<String> errors = new LinkedList<>();
	
	public String getSingleStringError() {
		StringBuilder sb = new StringBuilder();
		if (!errors.isEmpty()) {
			errors.forEach(e -> { sb.append(e); sb.append(", "); }); 
		}
		return sb.toString();
	}
	
	public Integer getNumErrors() {
		return errors.size();
	}
	
	public Boolean isValid() {
		return errors.size() < 1;
	}
}
