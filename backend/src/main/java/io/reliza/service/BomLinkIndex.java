/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;

import io.reliza.common.Utils;
import tools.jackson.databind.JsonNode;

/**
 * The purl index of a pinned CycloneDX document for the BOM-Link affects of a package VDR or VEX
 * (FDAW-7 3.6, functional F-2): {@code metadata.component} and then every element of the top-level
 * {@code components[]}, each followed by its own {@code components[]} at every depth, depth first in
 * document order. A node with a non-blank {@code bom-ref} and a non-blank {@code purl} lists its
 * bom-ref under {@link Utils#purlIdentity} of its purl (once per key); a node without either adds
 * nothing and its children are still walked. {@code services[]}, {@code dependencies[]} and
 * {@code compositions[]} are not walked. The walk is iterative, so a deeply nested document cannot
 * overflow the stack (security F-7).
 */
public final class BomLinkIndex {

	/** At most this many BOM-Link affects per finding (security F-7); the purl affect always stays. */
	public static final int MAX_LINKS_PER_FINDING = 100;

	private static final BomLinkIndex EMPTY = new BomLinkIndex(Map.of());

	private final Map<String, List<String>> refsByIdentity;

	private BomLinkIndex(Map<String, List<String>> refsByIdentity) {
		this.refsByIdentity = refsByIdentity;
	}

	/** The index of {@code document}; a null document or one without components answers {@code []} for every purl. */
	public static BomLinkIndex of(JsonNode document) {
		if (null == document || !document.isObject()) return EMPTY;
		Map<String, List<String>> refs = new HashMap<>();
		// roots in document order: metadata.component, then components[]
		List<JsonNode> roots = new ArrayList<>();
		JsonNode metadata = document.get("metadata");
		if (null != metadata && metadata.isObject()) {
			JsonNode root = metadata.get("component");
			if (null != root && root.isObject()) roots.add(root);
		}
		JsonNode top = document.get("components");
		if (null != top && top.isArray()) top.forEach(roots::add);
		// depth first, document order: a stack of child iterators
		Deque<Iterator<JsonNode>> stack = new ArrayDeque<>();
		stack.push(roots.iterator());
		while (!stack.isEmpty()) {
			Iterator<JsonNode> it = stack.peek();
			if (!it.hasNext()) {
				stack.pop();
				continue;
			}
			JsonNode node = it.next();
			if (null == node || !node.isObject()) continue;
			index(node, refs);
			JsonNode children = node.get("components");
			if (null != children && children.isArray() && !children.isEmpty()) {
				List<JsonNode> list = new ArrayList<>();
				children.forEach(list::add);
				stack.push(list.iterator());
			}
		}
		return new BomLinkIndex(refs);
	}

	private static void index(JsonNode node, Map<String, List<String>> refs) {
		String bomRef = textOf(node.get("bom-ref"));
		String purl = textOf(node.get("purl"));
		if (StringUtils.isBlank(bomRef) || StringUtils.isBlank(purl)) return;
		List<String> list = refs.computeIfAbsent(Utils.purlIdentity(purl), k -> new ArrayList<>());
		if (!list.contains(bomRef)) list.add(bomRef);
	}

	private static String textOf(JsonNode n) {
		return null != n && n.isString() ? n.asString() : null;
	}

	/** The bom-refs of the components whose purl has the identity of {@code purl}, in document order; never null. */
	public List<String> bomRefsOf(String purl) {
		if (StringUtils.isBlank(purl)) return List.of();
		List<String> l = refsByIdentity.get(Utils.purlIdentity(purl));
		return null == l ? List.of() : List.copyOf(l);
	}

	/** The whole-BOM BOM-Link {@code urn:cdx:<serial>/<version>}, the serial as the bare uuid. */
	public static String bomLink(UUID serial, int version) {
		return "urn:cdx:" + serial + "/" + version;
	}

	/**
	 * The BOM-Link of one element, {@code urn:cdx:<serial>/<version>#<bom-ref>}, the bom-ref
	 * percent-encoded as RFC 3986 encodes a fragment (security F-7): every UTF-8 octet outside
	 * unreserved, sub-delims, ":", "@", "/" and "?" is written as %XX, "%" included.
	 */
	public static String elementLink(UUID serial, int version, String bomRef) {
		return bomLink(serial, version) + "#" + encodeFragment(bomRef);
	}

	static String encodeFragment(String s) {
		StringBuilder sb = new StringBuilder();
		for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
			int c = b & 0xff;
			if (fragmentSafe(c)) {
				sb.append((char) c);
			} else {
				sb.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
						.append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
			}
		}
		return sb.toString();
	}

	/**
	 * The exact inverse of {@link #encodeFragment} (FDAW-25 F-32): each %XX becomes one octet, every other
	 * character stays, "+" included, and the octets read as UTF-8.
	 */
	public static String decodeFragment(String s) {
		ByteArrayOutputStream octets = new ByteArrayOutputStream(s.length());
		for (int i = 0; i < s.length();) {
			int hi = i + 2 < s.length() && '%' == s.charAt(i) ? Character.digit(s.charAt(i + 1), 16) : -1;
			int lo = hi < 0 ? -1 : Character.digit(s.charAt(i + 2), 16);
			if (lo >= 0) {
				octets.write(hi << 4 | lo);
				i += 3;
			} else {
				int cp = s.codePointAt(i);
				octets.writeBytes(Character.toString(cp).getBytes(StandardCharsets.UTF_8));
				i += Character.charCount(cp);
			}
		}
		return octets.toString(StandardCharsets.UTF_8);
	}

	private static boolean fragmentSafe(int c) {
		if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) return true;
		return "-._~!$&'()*+,;=:@/?".indexOf(c) >= 0;
	}
}
