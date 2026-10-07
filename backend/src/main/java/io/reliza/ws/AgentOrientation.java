/**
 * Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
 */
package io.reliza.ws;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.core.io.ClassPathResource;

/**
 * The agent orientation, split by action (task RD3-10): a core every agent reads, and one section per
 * action an agent may take, read when it is about to take it. Sections are named for actions, never for
 * a board's roles, because the document is read outside boards too and every board names its own roles.
 *
 * <p>The core's "Where to read what" table is the one list of sections and their order: the full
 * document, served at the old URL so every existing anchor still resolves, is the core followed by each
 * section in that order.
 */
public final class AgentOrientation {

	private AgentOrientation() {}

	static final String DIR = "static/agents/orientation/";
	public static final String CORE = "core";

	/** One row of the core's table: when to read the section, and its name. */
	public record Section(String key, String when) {}

	private static final Pattern ROW = Pattern.compile("^\\| (.+?) \\| `([a-z0-9-]+)` \\|$", Pattern.MULTILINE);

	private static String read(String name) throws IOException {
		try (InputStream in = new ClassPathResource(DIR + name + ".md").getInputStream()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	public static String core() throws IOException {
		return read(CORE);
	}

	/** The sections in the core's table, in its order. */
	public static List<Section> sections() throws IOException {
		String core = core();
		int at = core.indexOf("## Where to read what");
		List<Section> out = new ArrayList<>();
		if (at < 0) return out;
		Matcher m = ROW.matcher(core.substring(at));
		while (m.find()) out.add(new Section(m.group(2), m.group(1)));
		return out;
	}

	/** The core, or one section by name; empty for a name the table does not list. */
	public static Optional<String> section(String key) throws IOException {
		if (CORE.equals(key)) return Optional.of(core());
		for (Section s : sections()) {
			if (s.key().equals(key)) return Optional.of(read(key));
		}
		return Optional.empty();
	}

	/** The whole document: the core, then every section in the table's order. */
	public static String full() throws IOException {
		StringBuilder sb = new StringBuilder(core());
		for (Section s : sections()) {
			sb.append('\n').append(read(s.key()));
		}
		return sb.toString();
	}
}
