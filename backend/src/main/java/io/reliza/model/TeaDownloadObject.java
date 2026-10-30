/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

/**
 * What a TEA artifact download serves (task TEA-10): the revision's bytes, or its detached signature.
 * The TEA surface picks it from the path, and the download log records it by name; the GraphQL
 * {@code DownloadConfig.teaObject} field stays a String carrying that name.
 */
public enum TeaDownloadObject { CONTENT, SIGNATURE }
