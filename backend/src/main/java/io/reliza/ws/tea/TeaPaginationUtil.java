/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws.tea;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * Helper for TEA cursor pagination.
 *
 * The TEA {@code pageToken} is opaque to clients, so we encode an absolute
 * offset inside it (base64 of the next offset). This lets the cursor be backed
 * by simple offset/limit slicing today and swapped to keyset pagination later
 * without changing the wire contract. {@code nextPageToken} is always emitted
 * (the spec marks it required); on the final page it points just past the end,
 * so a follow-up request yields an empty page with {@code hasNext=false}.
 */
public final class TeaPaginationUtil {

	private TeaPaginationUtil() {}

	public static final long DEFAULT_PAGE_SIZE = 25L;

	/** A page of results plus the cursor fields the TEA responses require. */
	public record TeaPage<T>(List<T> items, boolean hasNext, String nextPageToken) {}

	/** Decode an incoming pageToken to a 0-based offset; null/blank/garbage -> 0. */
	public static long decodeOffset(String pageToken) {
		if (pageToken == null || pageToken.isBlank()) {
			return 0L;
		}
		try {
			String raw = new String(Base64.getUrlDecoder().decode(pageToken), StandardCharsets.UTF_8);
			long offset = Long.parseLong(raw.trim());
			return offset < 0 ? 0L : offset;
		} catch (RuntimeException e) {
			// Treat an unparseable token as the first page rather than erroring.
			return 0L;
		}
	}

	/** Encode an absolute offset into an opaque token. */
	public static String encodeOffset(long offset) {
		return Base64.getUrlEncoder().withoutPadding()
				.encodeToString(Long.toString(offset).getBytes(StandardCharsets.UTF_8));
	}

	public static final long MAX_PAGE_SIZE = 100L;

	/** Clamp the requested page size to the spec-allowed range, defaulting when absent. */
	public static long effectiveSize(Long pageSize) {
		if (pageSize == null || pageSize <= 0) {
			return DEFAULT_PAGE_SIZE;
		}
		return Math.min(pageSize, MAX_PAGE_SIZE);
	}

	/**
	 * Slice an already-materialized list by the cursor. Use for endpoints that
	 * load the full collection (small N) — products, per-release collections, etc.
	 */
	public static <T> TeaPage<T> slice(List<T> all, String pageToken, Long pageSize) {
		long size = effectiveSize(pageSize);
		int total = all.size();
		int from = (int) Math.min(decodeOffset(pageToken), total);
		int to = (int) Math.min((long) from + size, total);
		return new TeaPage<>(all.subList(from, to), to < total, encodeOffset(to));
	}

	/**
	 * Build a cursor page from a DB result fetched with {@code limit = pageSize + 1}
	 * at {@code offset}. Use for endpoints that page at the DB layer (e.g. product
	 * releases) so the full set is never materialized.
	 */
	public static <T> TeaPage<T> fromOverfetch(List<T> fetched, long offset, Long pageSize) {
		long size = effectiveSize(pageSize);
		boolean hasNext = fetched.size() > size;
		List<T> items = hasNext ? fetched.subList(0, (int) size) : fetched;
		return new TeaPage<>(items, hasNext, encodeOffset(offset + items.size()));
	}
}
