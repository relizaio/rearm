/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * FDAW-47 (architecture round 2, section 4 row 4): both log4j2 files route {@code org.flywaydb} at
 * INFO, non-additive, to {@code AsyncConsole}, so Flyway's {@code DB: <text>} line for a migration's
 * {@code RAISE NOTICE} keeps reaching the deploy log if Root is made quieter; the file was extended,
 * not rewritten.
 */
class Log4j2FlywayLoggerConfigTest {

	private static final List<String> PRE_EXISTING_LOGGERS = List.of("io.reliza", "org.springframework",
			"org.hibernate", "com.netflix.graphql.dgs", "org.springframework.security");

	private static Document read(String resource) throws Exception {
		try (InputStream in = Log4j2FlywayLoggerConfigTest.class.getResourceAsStream(resource)) {
			assertNotNull(in, resource + " is on the classpath");
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			return factory.newDocumentBuilder().parse(in);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"/log4j2-json.xml", "/log4j2-plain.xml"})
	void flywayLogsAtInfoToTheAsyncConsole(String resource) throws Exception {
		Document doc = read(resource);
		XPath xpath = XPathFactory.newInstance().newXPath();

		NodeList flyway = (NodeList) xpath.evaluate("/Configuration/Loggers/Logger[@name='org.flywaydb']", doc,
				XPathConstants.NODESET);
		assertEquals(1, flyway.getLength(), resource);
		Element logger = (Element) flyway.item(0);
		assertEquals("info", logger.getAttribute("level"), resource);
		assertEquals("false", logger.getAttribute("additivity"), resource);
		NodeList refs = (NodeList) xpath.evaluate("AppenderRef", logger, XPathConstants.NODESET);
		assertEquals(1, refs.getLength(), resource);
		assertEquals("AsyncConsole", ((Element) refs.item(0)).getAttribute("ref"), resource);

		for (String name : PRE_EXISTING_LOGGERS) {
			assertEquals(1.0, (Double) xpath.evaluate("count(/Configuration/Loggers/Logger[@name='" + name + "'])", doc,
					XPathConstants.NUMBER), resource + " still configures " + name);
		}
		assertEquals(1.0, (Double) xpath.evaluate("count(/Configuration/Loggers/Root[@level='info'])", doc,
				XPathConstants.NUMBER), resource + " keeps Root at info");
	}
}
