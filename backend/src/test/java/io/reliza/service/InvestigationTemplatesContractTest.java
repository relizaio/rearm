/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static io.reliza.service.RoleTemplates.template;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

/**
 * Investigations in the worker templates (task RD4-12, design §3.5), one rule across three templates and so its own
 * class: each says an investigation delivers a report and no code, and how a role whose commissions allow it asks
 * another for one and gets the report back pinned. The same words in each, so a board's roles are told one thing.
 */
class InvestigationTemplatesContractTest {

	private static final String[] WORKERS = {"coder-fix.md", "reviewer.md", "tester.md"};

	@Test
	void everyWorkerTemplateSaysAnInvestigationDeliversAReportAndNoCode() throws IOException {
		for (String name : WORKERS) {
			String doc = template(name);
			assertTrue(doc.contains("## Investigations"), name);
			assertTrue(doc.contains("A task whose kind is INVESTIGATION asks you for a report, not code."), name);
			assertTrue(doc.contains("sign off PASSED with the report as your output: the board refuses the pass without it."), name);
			assertTrue(doc.contains("Link no PRs and change no code; an investigation has no delivery step."), name);
			assertTrue(doc.contains("The report's elements are references only: cite ids, define none."), name);
		}
	}

	@Test
	void everyWorkerTemplateSaysHowToCommissionAndThatTheReportComesBackPinned() throws IOException {
		for (String name : WORKERS) {
			String doc = template(name);
			assertTrue(doc.contains("When your role's `commissions` name another role"), name);
			assertTrue(doc.contains("`rearm agent task commission --session <session> --board <board> --role <role>"), name);
			assertTrue(doc.contains("--from-task <your task>"), name);
			assertTrue(doc.contains("The report comes back pinned as an input on your task."), name);
			assertTrue(doc.contains("return your hop with `--reason BLOCKED_ON_DEPENDENCY`"), name);
		}
	}

	/** Design round 2 §2: a cancelled investigation comes back too, and the asker decides what follows. */
	@Test
	void everyWorkerTemplateSaysACancelledInvestigationComesBackWithNoReport() throws IOException {
		for (String name : WORKERS) {
			String doc = template(name);
			assertTrue(doc.contains("If the investigation is cancelled instead, the task comes back with no report:"
					+ " its reports returned list the cancel and its note. Ask again, go on without it, or return the hop."),
					name);
		}
	}

	@Test
	void theSectionReadsTheSameInEachTemplate() throws IOException {
		String first = section(WORKERS[0]);
		for (String name : WORKERS) assertEquals(first, section(name), name);
	}

	private static String section(String name) throws IOException {
		String doc = template(name);
		int at = doc.indexOf("## Investigations");
		return doc.substring(at, doc.indexOf("## ", at + 3));
	}
}
