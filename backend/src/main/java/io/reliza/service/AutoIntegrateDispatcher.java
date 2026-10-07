/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Off-request hop for product auto-integration. Lives in the shared
 * {@code io.reliza.service} package (not {@code .oss}) so ReARM CE can reuse it
 * directly; it depends only on {@link ReleaseService}, the shared bridge.
 *
 * <p>Exists as its own bean on purpose: the {@code @Async} boundary has to be
 * crossed bean-to-bean, not via a self-invocation. A self-call to an
 * {@code @Async} method on the same bean (through a {@code @Lazy self} proxy)
 * stacks the async and transaction proxies in a way that drops the
 * {@code @Transactional} advice on the nested feature-set / marker writes — the
 * worker then runs with no active transaction and every {@code @Modifying}
 * write fails with TransactionRequiredException. Calling back into the release
 * service from here goes through its clean transaction proxy.
 *
 * <p>Runs on the bounded {@code autoIntegrateExecutor} so background
 * integration can never exhaust the Hikari pool (the failure this whole change
 * fixes). It is NOT overridden in tests: TestAsyncConfig replaces only the
 * default {@code taskExecutor}, so this hop runs on a real background thread
 * there too, and tests that trigger it wait for its result.
 */
@Service
public class AutoIntegrateDispatcher {

	@Autowired
	@Lazy
	private ReleaseService releaseService;

	@Async("autoIntegrateExecutor")
	public void asyncProcess(UUID releaseUuid) {
		releaseService.processAutoIntegrateForRelease(releaseUuid);
	}

	/** The batch counterpart of {@link #asyncProcess}: one hop for a whole batch's queued releases. */
	@Async("autoIntegrateExecutor")
	public void asyncProcessBatch(List<UUID> releaseUuids) {
		releaseService.processAutoIntegrateForBatch(releaseUuids);
	}
}
