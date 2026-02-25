/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.common;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

public class DateDeserializer extends StdDeserializer<ZonedDateTime> {
    
    /**
     * 
     */
    private static final long serialVersionUID = 5843953953572766515L;
    private static final Pattern NO_COLON_PATTERN = Pattern.compile("\\+\\d{4}$");
    
    public DateDeserializer() {
	super(ZonedDateTime.class);
    }

    @Override
    public ZonedDateTime deserialize(JsonParser p, DeserializationContext ctxt)
	    throws IOException, JsonProcessingException {
	ZonedDateTime retValue = null;
	String dateString = p.readValueAs(String.class);
	if (StringUtils.isBlank(dateString)) {
	    return null;
	}
	Matcher m = NO_COLON_PATTERN.matcher(dateString);
	if (m.find()) {
	    // inject colon
	    dateString = dateString.replaceFirst("\\d{2}$", ":" + dateString.substring(dateString.length() - 2));
	}
	retValue = ZonedDateTime.parse(dateString);
	return retValue;
    }

}
