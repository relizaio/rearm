/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.testsupport;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Clears the security context after every test method in the suite (task df51a6fe).
 *
 * <p>Unit tests that install a mocked {@code SecurityContext} and never clear it leave it on the
 * thread, and a later test in the same JVM that writes an authentication through
 * {@code getContext()} writes into the mock and runs unauthenticated -- a failure that depends on
 * the order the suite ran in. Registered in {@code META-INF/services} and picked up by
 * auto-detection, so it applies to every test without an annotation: the next test that forgets
 * to clear is covered too.
 */
public class ClearSecurityContextExtension implements AfterEachCallback {

	@Override
	public void afterEach(ExtensionContext context) {
		SecurityContextHolder.clearContext();
	}
}
