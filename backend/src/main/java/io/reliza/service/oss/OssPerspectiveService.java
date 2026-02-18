/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service.oss;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import io.reliza.model.RelizaObject;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class OssPerspectiveService {
	
	/**
	 * Part of ReARM Pro only
	 * @param uuid
	 * @return
	 */
	public Optional<RelizaObject> getPerspectiveData (UUID uuid) {
		return Optional.empty();
	}
}
