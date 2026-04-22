/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service.oss;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import io.reliza.common.CommonVariables.PerspectiveType;
import io.reliza.model.RelizaObject;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class OssPerspectiveService {
	
	public static class PerspectiveData implements RelizaObject {
		public PerspectiveType getType() {
			return null;
		}

		@Override
		public UUID getUuid() {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public UUID getOrg() {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public UUID getResourceGroup() {
			// TODO Auto-generated method stub
			return null;
		}
	}
	/**
	 * Part of ReARM Pro only
	 * @param uuid
	 * @return
	 */
	public Optional<PerspectiveData> getPerspectiveData (UUID uuid) {
		return Optional.empty();
	}
}
