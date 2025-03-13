/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.net.URI;

/**
 * This class contains data model to stream back to client when instance requests for releases to be deployed
 * @author pavel
 *
 */

public record TextPayload(String text, URI uri) { 
	public static TextPayload COMMA_SEPARATOR_TEXT_PAYLOAD = new TextPayload(", ", null);
	public static TextPayload WHITESPACE_SEPARATOR_TEXT_PAYLOAD = new TextPayload(" ", null);
}

