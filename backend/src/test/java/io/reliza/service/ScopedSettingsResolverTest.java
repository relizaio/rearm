/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.CommonVariables.PerspectiveType;
import io.reliza.model.ComponentData;
import io.reliza.model.ScopedResolutionStatus;
import io.reliza.model.ScopedSettingsMode;
import io.reliza.model.ScopedSettingsSource;
import io.reliza.model.SettingsScope;
import io.reliza.service.ScopedSettingsResolver.ScopedResolution;
import io.reliza.service.oss.OssPerspectiveService;
import io.reliza.service.oss.OssPerspectiveService.TeaPerspectiveRef;

/**
 * Task TEA-27, design 4.1: the one scoped settings engine against a generic in-memory store, so the
 * walk is pinned without any settings type of its own (threat model M1).
 */
class ScopedSettingsResolverTest {

	/** A row of the generic store; {@code normalized} marks a copy the store's normalization made. */
	record Row(UUID uuid, SettingsScope scope, UUID object, ScopedSettingsMode mode, UUID followed, boolean normalized) {
		Row withNormalized() {
			return new Row(uuid, scope, object, mode, followed, true);
		}
	}

	/** The store: the rows below, a built-in default when {@code hasDefault}, a marking normalization when asked. */
	private final class Store implements ScopedSettingsStore<Row> {
		boolean hasDefault = true;
		boolean normalizes = false;
		final Row builtIn = new Row(null, SettingsScope.ORGANIZATION, null, null, null, false);

		@Override public String label() { return "test settings"; }
		@Override public Optional<Row> organizationRow(UUID o) {
			return rows.stream().filter(r -> r.scope() == SettingsScope.ORGANIZATION && org.equals(o)).findFirst();
		}
		@Override public Optional<Row> perspectiveRow(UUID o, UUID perspective) {
			return rows.stream().filter(r -> r.scope() == SettingsScope.PERSPECTIVE && perspective.equals(r.object()))
					.findFirst();
		}
		@Override public List<Row> perspectiveRows(UUID o, Collection<UUID> perspectives) {
			return rows.stream().filter(r -> r.scope() == SettingsScope.PERSPECTIVE && perspectives.contains(r.object()))
					.toList();
		}
		@Override public Optional<Row> componentRow(UUID o, UUID component) {
			return rows.stream().filter(r -> r.scope() == SettingsScope.COMPONENT && component.equals(r.object()))
					.findFirst();
		}
		@Override public Optional<Row> builtInDefault(UUID o) { return hasDefault ? Optional.of(builtIn) : Optional.empty(); }
		@Override public UUID uuidOf(Row row) { return row.uuid(); }
		@Override public UUID objectOf(Row row) { return row.object(); }
		@Override public ScopedSettingsMode modeOf(Row row) { return row.mode(); }
		@Override public UUID followedPerspectiveOf(Row row) { return row.followed(); }
		@Override public Row normalized(UUID o, Row row) { return normalizes ? row.withNormalized() : row; }
	}

	/** In-memory appender on the engine's own logger. */
	private static class CapturingAppender extends AbstractAppender {
		private final List<LogEvent> events = Collections.synchronizedList(new LinkedList<>());
		CapturingAppender() { super("scoped-resolver-" + UUID.randomUUID(), null, null, true, Property.EMPTY_ARRAY); }
		@Override public void append(LogEvent event) { events.add(event.toImmutable()); }
	}

	private final UUID org = UUID.randomUUID();
	private final List<Row> rows = new ArrayList<>();
	private final TeaPerspectiveRef alpha = new TeaPerspectiveRef(UUID.randomUUID(), "Alpha", PerspectiveType.PERSPECTIVE);
	private final TeaPerspectiveRef beta = new TeaPerspectiveRef(UUID.randomUUID(), "beta", PerspectiveType.PERSPECTIVE);
	private ScopedSettingsResolver engine;
	private OssPerspectiveService perspectives;
	private GetComponentService components;
	private Store store;
	private ComponentData cd;
	private CapturingAppender logs;
	private Level previousLevel;

	@BeforeEach
	void setUp() {
		engine = new ScopedSettingsResolver();
		perspectives = mock(OssPerspectiveService.class);
		components = mock(GetComponentService.class);
		ReflectionTestUtils.setField(engine, "ossPerspectiveService", perspectives);
		ReflectionTestUtils.setField(engine, "getComponentService", components);
		store = new Store();
		cd = new ComponentData();
		cd.setUuid(UUID.randomUUID());
		cd.setOrg(org);
		cd.setName("widget");
		lenient().when(components.getComponentData(cd.getUuid())).thenReturn(Optional.of(cd));
		candidates(alpha, beta);
		lenient().when(perspectives.teaPerspectiveRefs(eq(org), anyCollection())).thenAnswer(inv -> {
			Collection<UUID> wanted = inv.getArgument(1);
			return wanted.stream().flatMap(u -> List.of(alpha, beta).stream().filter(k -> k.uuid().equals(u))).toList();
		});

		logs = new CapturingAppender();
		logs.start();
		org.apache.logging.log4j.core.Logger logger = (org.apache.logging.log4j.core.Logger) LogManager
				.getLogger(ScopedSettingsResolver.class);
		previousLevel = logger.getLevel();
		logger.setLevel(Level.WARN);
		logger.addAppender(logs);
	}

	@AfterEach
	void tearDown() {
		org.apache.logging.log4j.core.Logger logger = (org.apache.logging.log4j.core.Logger) LogManager
				.getLogger(ScopedSettingsResolver.class);
		logger.removeAppender(logs);
		logger.setLevel(previousLevel);
		logs.stop();
	}

	private void candidates(TeaPerspectiveRef... refs) {
		lenient().when(perspectives.teaPerspectivesOfComponent(any())).thenReturn(List.of(refs));
	}

	private Row add(SettingsScope scope, UUID object, ScopedSettingsMode mode, UUID followed) {
		Row r = new Row(UUID.randomUUID(), scope, object, mode, followed, false);
		rows.add(r);
		return r;
	}

	private Row orgRow() { return add(SettingsScope.ORGANIZATION, null, null, null); }
	private Row perspectiveRow(TeaPerspectiveRef p) { return add(SettingsScope.PERSPECTIVE, p.uuid(), null, null); }
	private Row own(ScopedSettingsMode mode, UUID followed) { return add(SettingsScope.COMPONENT, cd.getUuid(), mode, followed); }

	private long warnings() {
		return logs.events.stream().filter(e -> e.getLevel() == Level.WARN).count();
	}

	private ScopedResolution<Row> resolve() {
		return engine.resolveForComponent(store, cd, null, null);
	}

	@Test
	void anOwnOverrideRowWinsWithoutReadingTheStoredOne() {
		orgRow();
		perspectiveRow(alpha);
		own(ScopedSettingsMode.FOLLOW_PERSPECTIVE, alpha.uuid());
		Row whatIf = new Row(UUID.randomUUID(), SettingsScope.COMPONENT, cd.getUuid(), ScopedSettingsMode.OVERRIDE, null,
				false);
		ScopedResolution<Row> r = engine.resolveForComponentWithRow(store, cd, whatIf);
		assertEquals(ScopedResolutionStatus.RESOLVED, r.status());
		assertEquals(ScopedSettingsSource.COMPONENT, r.source());
		assertEquals(cd.getUuid(), r.sourceObject());
		assertSame(whatIf, r.settings());

		Row stored = own(ScopedSettingsMode.OVERRIDE, null);
		rows.removeIf(x -> x.mode() == ScopedSettingsMode.FOLLOW_PERSPECTIVE);
		assertEquals(stored, resolve().settings());
	}

	@Test
	void aFollowOfACandidateWithARowResolvesFollowedPerspective() {
		perspectiveRow(alpha);
		Row b = perspectiveRow(beta);
		own(ScopedSettingsMode.FOLLOW_PERSPECTIVE, beta.uuid());
		ScopedResolution<Row> r = resolve();
		assertEquals(ScopedSettingsSource.FOLLOWED_PERSPECTIVE, r.source());
		assertEquals(beta.uuid(), r.sourceObject());
		assertEquals(b, r.settings());
		assertEquals(0, warnings());
	}

	@Test
	void aFollowThatCannotBeHonouredIsResolvedPastWithOneWarning() {
		Row o = orgRow();
		// no candidate
		TeaPerspectiveRef gone = new TeaPerspectiveRef(UUID.randomUUID(), "Gone", PerspectiveType.PERSPECTIVE);
		Row follow = own(ScopedSettingsMode.FOLLOW_PERSPECTIVE, gone.uuid());
		assertEquals(o, resolve().settings());
		assertEquals(1, warnings());
		// a candidate without a row
		rows.remove(follow);
		follow = own(ScopedSettingsMode.FOLLOW_PERSPECTIVE, alpha.uuid());
		assertEquals(ScopedSettingsSource.ORGANIZATION, resolve().source());
		assertEquals(2, warnings());
		// a row read as absent
		Row a = perspectiveRow(alpha);
		Row b = perspectiveRow(beta);
		ScopedResolution<Row> without = engine.resolveForComponentWithoutRows(store, cd, Set.of(a.uuid()));
		assertEquals(ScopedSettingsSource.PERSPECTIVE, without.source(), "past the follow to the one other candidate row");
		assertEquals(b, without.settings());
		assertEquals(3, warnings(), "one warning per resolution");
	}

	@Test
	void oneCandidateRowResolvesPerspective() {
		orgRow();
		Row b = perspectiveRow(beta);
		ScopedResolution<Row> r = resolve();
		assertEquals(ScopedSettingsSource.PERSPECTIVE, r.source());
		assertEquals(beta.uuid(), r.sourceObject());
		assertEquals(b, r.settings());
	}

	@Test
	void twoCandidateRowsAreAConflictInNameOrder() {
		orgRow();
		perspectiveRow(beta);
		perspectiveRow(alpha);
		ScopedResolution<Row> r = resolve();
		assertEquals(ScopedResolutionStatus.CONFLICT, r.status());
		assertNull(r.settings());
		assertNull(r.sourceObject());
		assertEquals(List.of(alpha, beta), r.conflictingPerspectives());
	}

	@Test
	void theRecordedRowSettlesAConflictOnlyWhenItIsAmongTheRows() {
		Row o = orgRow();
		perspectiveRow(alpha);
		Row b = perspectiveRow(beta);
		ScopedResolution<Row> recorded = engine.resolveForComponent(store, cd, b.uuid());
		assertEquals(ScopedResolutionStatus.RESOLVED, recorded.status());
		assertEquals(ScopedSettingsSource.PERSPECTIVE, recorded.source());
		assertEquals(beta.uuid(), recorded.sourceObject());
		assertEquals(b, recorded.settings());
		assertEquals(ScopedResolutionStatus.CONFLICT, engine.resolveForComponent(store, cd, o.uuid()).status());
		assertEquals(ScopedResolutionStatus.CONFLICT, engine.resolveForComponent(store, cd, UUID.randomUUID()).status());
	}

	@Test
	void thePreferredPerspectiveSettlesAConflictOnlyWhenItIsAmongTheRows() {
		// F-1: the second request-time fallback.
		perspectiveRow(alpha);
		Row b = perspectiveRow(beta);
		ScopedResolution<Row> preferred = engine.resolveForComponent(store, cd, null, beta.uuid());
		assertEquals(ScopedResolutionStatus.RESOLVED, preferred.status());
		assertEquals(ScopedSettingsSource.PERSPECTIVE, preferred.source());
		assertEquals(beta.uuid(), preferred.sourceObject());
		assertEquals(b, preferred.settings());

		ScopedResolution<Row> outside = engine.resolveForComponent(store, cd, null, UUID.randomUUID());
		assertEquals(ScopedResolutionStatus.CONFLICT, outside.status());
		assertEquals(List.of(alpha, beta), outside.conflictingPerspectives());
	}

	@Test
	void theRecordedRowWinsOverThePreferredPerspective() {
		Row a = perspectiveRow(alpha);
		perspectiveRow(beta);
		ScopedResolution<Row> r = engine.resolveForComponent(store, cd, a.uuid(), beta.uuid());
		assertEquals(a, r.settings());
		assertEquals(alpha.uuid(), r.sourceObject());
	}

	@Test
	void aPreferenceChangesNothingWhenOneCandidateRowOrAnOwnRowResolves() {
		Row a = perspectiveRow(alpha);
		assertEquals(a, engine.resolveForComponent(store, cd, null, beta.uuid()).settings(),
				"one candidate row: the preference does not reach past it");
		assertEquals(alpha.uuid(), engine.resolveForComponent(store, cd, null, beta.uuid()).sourceObject());
		perspectiveRow(beta);
		Row mine = own(ScopedSettingsMode.OVERRIDE, null);
		ScopedResolution<Row> r = engine.resolveForComponent(store, cd, null, beta.uuid());
		assertEquals(ScopedSettingsSource.COMPONENT, r.source());
		assertEquals(mine, r.settings());
	}

	@Test
	void theOrganizationRowThenTheBuiltInDefaultThenNotFound() {
		assertEquals(ScopedSettingsSource.DEFAULT, resolve().source());
		assertSame(store.builtIn, resolve().settings());
		Row o = orgRow();
		assertEquals(ScopedSettingsSource.ORGANIZATION, resolve().source());
		assertEquals(o, resolve().settings());

		rows.clear();
		store.hasDefault = false;
		ScopedResolution<Row> none = resolve();
		assertEquals(ScopedResolutionStatus.NOT_FOUND, none.status());
		assertNull(none.settings());
		assertNull(none.source());
		assertNull(none.sourceObject());
		assertTrue(none.conflictingPerspectives().isEmpty());
		assertEquals(ScopedResolutionStatus.NOT_FOUND,
				engine.effectiveOfScope(store, org, SettingsScope.ORGANIZATION, null).status());
		assertEquals(ScopedResolutionStatus.NOT_FOUND,
				engine.parentOfScope(store, org, SettingsScope.ORGANIZATION, null).status());
	}

	@Test
	void effectiveAndParentOfEachScope() {
		own(ScopedSettingsMode.OVERRIDE, null);
		Row a = perspectiveRow(alpha);
		perspectiveRow(beta);
		assertEquals(ScopedResolutionStatus.CONFLICT,
				engine.parentOfScope(store, org, SettingsScope.COMPONENT, cd.getUuid()).status(),
				"the component's own row is left out");
		assertEquals(ScopedSettingsSource.COMPONENT,
				engine.effectiveOfScope(store, org, SettingsScope.COMPONENT, cd.getUuid()).source());

		assertEquals(ScopedSettingsSource.DEFAULT,
				engine.parentOfScope(store, org, SettingsScope.PERSPECTIVE, alpha.uuid()).source());
		Row o = orgRow();
		assertEquals(o, engine.parentOfScope(store, org, SettingsScope.PERSPECTIVE, alpha.uuid()).settings());
		assertEquals(ScopedSettingsSource.DEFAULT,
				engine.parentOfScope(store, org, SettingsScope.ORGANIZATION, null).source());

		ScopedResolution<Row> pEffective = engine.effectiveOfScope(store, org, SettingsScope.PERSPECTIVE, alpha.uuid());
		assertEquals(ScopedSettingsSource.PERSPECTIVE, pEffective.source());
		assertEquals(alpha.uuid(), pEffective.sourceObject());
		assertEquals(a, pEffective.settings());
		assertEquals(ScopedSettingsSource.ORGANIZATION,
				engine.effectiveOfScope(store, org, SettingsScope.PERSPECTIVE, UUID.randomUUID()).source(),
				"a perspective without a row resolves to the organization");
		assertEquals(ScopedSettingsSource.ORGANIZATION,
				engine.effectiveOfScope(store, org, SettingsScope.ORGANIZATION, null).source());
		assertEquals(ScopedSettingsSource.ORGANIZATION,
				engine.effectiveOfScope(store, org, SettingsScope.COMPONENT, UUID.randomUUID()).source(),
				"an unknown component resolves to the organization");
	}

	@Test
	void effectiveOfScopeWithoutRowsRemovesAnOrganizationAndAComponentRow() {
		Row o = orgRow();
		Row mine = own(ScopedSettingsMode.OVERRIDE, null);
		candidates();
		assertEquals(ScopedSettingsSource.COMPONENT,
				engine.effectiveOfScope(store, org, SettingsScope.COMPONENT, cd.getUuid()).source());
		ScopedResolution<Row> noComponentRow = engine.effectiveOfScopeWithoutRows(store, org, SettingsScope.COMPONENT,
				cd.getUuid(), Set.of(mine.uuid()));
		assertEquals(o, noComponentRow.settings());
		ScopedResolution<Row> neither = engine.effectiveOfScopeWithoutRows(store, org, SettingsScope.COMPONENT,
				cd.getUuid(), Set.of(mine.uuid(), o.uuid()));
		assertEquals(ScopedSettingsSource.DEFAULT, neither.source());
		store.hasDefault = false;
		assertEquals(ScopedResolutionStatus.NOT_FOUND, engine.effectiveOfScopeWithoutRows(store, org,
				SettingsScope.ORGANIZATION, null, Set.of(o.uuid())).status());
		Row a = perspectiveRow(alpha);
		assertEquals(ScopedResolutionStatus.NOT_FOUND, engine.effectiveOfScopeWithoutRows(store, org,
				SettingsScope.PERSPECTIVE, alpha.uuid(), Set.of(a.uuid(), o.uuid())).status());
		assertEquals(a, engine.effectiveOfScope(store, org, SettingsScope.PERSPECTIVE, alpha.uuid()).settings(),
				"unchanged");
	}

	@Test
	void theWhatIfOfAMembershipResolvesThroughTheGivenPerspectives() {
		Row a = perspectiveRow(alpha);
		perspectiveRow(beta);
		candidates();
		assertEquals(ScopedSettingsSource.DEFAULT, resolve().source());
		ScopedResolution<Row> joined = engine.resolveForComponentWithPerspectives(store, cd, List.of(alpha.uuid()));
		assertEquals(a, joined.settings());
		assertEquals(ScopedResolutionStatus.CONFLICT,
				engine.resolveForComponentWithPerspectives(store, cd, List.of(alpha.uuid(), beta.uuid())).status());
	}

	@Test
	void theNormalizationIsAppliedToEveryResolvedAnswer() {
		store.normalizes = true;
		assertTrue(resolve().settings().normalized(), "DEFAULT");
		orgRow();
		assertTrue(resolve().settings().normalized(), "ORGANIZATION");
		perspectiveRow(alpha);
		assertTrue(resolve().settings().normalized(), "PERSPECTIVE");
		Row b = perspectiveRow(beta);
		assertTrue(engine.resolveForComponent(store, cd, b.uuid()).settings().normalized(), "recorded");
		assertTrue(engine.resolveForComponent(store, cd, null, beta.uuid()).settings().normalized(), "preferred");
		own(ScopedSettingsMode.FOLLOW_PERSPECTIVE, beta.uuid());
		assertTrue(resolve().settings().normalized(), "FOLLOWED_PERSPECTIVE");
		rows.removeIf(r -> r.scope() == SettingsScope.COMPONENT);
		own(ScopedSettingsMode.OVERRIDE, null);
		assertTrue(resolve().settings().normalized(), "COMPONENT");
		assertTrue(engine.effectiveOfScope(store, org, SettingsScope.PERSPECTIVE, alpha.uuid()).settings().normalized());
		rows.forEach(r -> assertTrue(!r.normalized(), "the stored rows are untouched"));
	}
}
