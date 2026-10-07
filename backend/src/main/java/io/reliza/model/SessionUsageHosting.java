/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/

package io.reliza.model;

/**
 * Where a model was served from, for the rows that must be priced.
 *
 * <p>Not an identity dimension: the catalogue holds one row per model, and the Bedrock, Vertex and
 * Azure forms of a model id resolve onto it through the peel table rather than creating rows of
 * their own. They are billed differently though, so the deployment rides on the usage row and a
 * pricing entry may select on it.
 */
public enum SessionUsageHosting {
	DIRECT,
	BEDROCK,
	VERTEX,
	AZURE,
	OTHER
}
