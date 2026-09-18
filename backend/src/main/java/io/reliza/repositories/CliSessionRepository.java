/**
* Copyright 2019 - 2026 Reliza Incorporated. Licensed under MIT License.
* https://reliza.io
*/

package io.reliza.repositories;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.model.CliSession;

public interface CliSessionRepository extends JpaRepository<CliSession, UUID> {

	@Query(value = "SELECT * FROM rearm.cli_sessions WHERE user_code = :userCode AND status = 'PENDING'", nativeQuery = true)
	Optional<CliSession> findPendingByUserCode(@Param("userCode") String userCode);

	/** Locked for the poll's read-modify-write, so two concurrent polls cannot both deliver. */
	@Query(value = "SELECT * FROM rearm.cli_sessions WHERE device_code_hash = :hash FOR UPDATE", nativeQuery = true)
	Optional<CliSession> findByDeviceCodeHashForUpdate(@Param("hash") String hash);

	/** Locked: a refresh rotates the token and a reuse revokes the session, both read-modify-write on this row. */
	@Query(value = "SELECT * FROM rearm.cli_sessions WHERE refresh_token_hash = :hash FOR UPDATE", nativeQuery = true)
	Optional<CliSession> findByRefreshTokenHashForUpdate(@Param("hash") String hash);

	@Query(value = "SELECT * FROM rearm.cli_sessions WHERE previous_refresh_token_hash = :hash FOR UPDATE", nativeQuery = true)
	Optional<CliSession> findByPreviousRefreshTokenHashForUpdate(@Param("hash") String hash);

	/** Pending logins past their ten minutes; cheap enough to run on every start, throttled by the caller. */
	@Transactional
	@Modifying
	@Query(value = "DELETE FROM rearm.cli_sessions WHERE status = 'PENDING' AND expires_date < now()", nativeQuery = true)
	int purgeExpiredPending();

	@Query(value = "SELECT * FROM rearm.cli_sessions WHERE refresh_token_hash = :hash", nativeQuery = true)
	Optional<CliSession> findByRefreshTokenHash(@Param("hash") String hash);

	@Query(value = "SELECT * FROM rearm.cli_sessions WHERE \"user\" = :user AND status = 'ACTIVE' ORDER BY created_date DESC", nativeQuery = true)
	List<CliSession> findActiveByUser(@Param("user") UUID user);

	@Query(value = "SELECT * FROM rearm.cli_sessions WHERE api_key = :apiKey AND status = 'ACTIVE' ORDER BY created_date DESC", nativeQuery = true)
	List<CliSession> findActiveByApiKey(@Param("apiKey") UUID apiKey);

	@Query(value = "SELECT COUNT(*) FROM rearm.cli_sessions WHERE api_key = :apiKey AND status = 'ACTIVE' AND uuid <> :except", nativeQuery = true)
	long countOtherActiveByApiKey(@Param("apiKey") UUID apiKey, @Param("except") UUID except);

	/** Usage bookkeeping, at most once a minute per session so a busy CLI does not write on every call. */
	@Transactional
	@Modifying
	@Query(value = "UPDATE rearm.cli_sessions SET last_used_date = now() WHERE uuid = :uuid "
			+ "AND (last_used_date IS NULL OR last_used_date < now() - interval '1 minute')", nativeQuery = true)
	int touchLastUsed(@Param("uuid") UUID uuid);

	/** Pending requests past their ten minutes, and revoked / denied / expired sessions older than the retention. */
	@Transactional
	@Modifying
	@Query(value = "DELETE FROM rearm.cli_sessions WHERE (status = 'PENDING' AND expires_date < now()) "
			+ "OR (status IN ('REVOKED','DENIED') AND coalesce(revoked_date, created_date) < :before) "
			+ "OR (status = 'ACTIVE' AND expires_date < :before)", nativeQuery = true)
	int purge(@Param("before") ZonedDateTime before);
}
