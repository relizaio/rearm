/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Every raw {@code OM.convertValue(} left under {@code ws/} is classified (task TPF-63, architecture-1
 * section 4 row 4). A resolver converting its own argument calls {@code InputConversion}; a raw call is
 * either stored data (a server fault, {@link #STORED}) or a client-input site a sibling task still has
 * to move ({@link #WS_PENDING}: TPF-64; {@link #OSS_SAAS_PENDING}: TPF-65). A new raw call fails here
 * until it is classified. Comment lines (starting with {@code //} or {@code *}) are not counted.
 */
class InputConversionCoverageTest {

	private static final Path WS = Path.of("src/main/java/io/reliza/ws");

	/** Client-input sites under ws/ itself; emptied by TPF-64. */
	private static final Map<String, Integer> WS_PENDING = Map.ofEntries();

	/** Stored or internal data: a failure is the server's own defect and stays a server error. */
	private static final Map<String, Integer> STORED = Map.ofEntries(
			Map.entry("ComponentDataFetcher.java", 1),
			Map.entry("NotificationDataFetcher.java", 4));

	/** Client-input sites under ws/oss and ws/saas; TPF-65 emptied it. */
	private static final Map<String, Integer> OSS_SAAS_PENDING = Map.of();

	private static long rawConversions(Path file) {
		try {
			return Files.readAllLines(file).stream()
					.filter(l -> l.contains("OM.convertValue("))
					.map(String::strip)
					.filter(l -> !l.startsWith("//") && !l.startsWith("*"))
					.count();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	@Test
	void everyRawConversionUnderWsIsClassified() throws IOException {
		Map<String, Integer> expected = new TreeMap<>();
		for (Map<String, Integer> constant : List.of(WS_PENDING, STORED, OSS_SAAS_PENDING)) {
			constant.forEach((file, n) -> expected.merge(file, n, Integer::sum));
		}
		Map<String, Integer> found = new TreeMap<>();
		try (Stream<Path> files = Files.walk(WS)) {
			files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
				long n = rawConversions(p);
				String key = WS.relativize(p).toString().replace('\\', '/');
				if (n > 0 || expected.containsKey(key)) found.put(key, (int) n);
			});
		}
		assertEquals(expected, found);
	}
}
