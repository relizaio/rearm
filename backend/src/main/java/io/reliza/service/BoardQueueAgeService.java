/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.ZonedDateTime;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.TaskStatus;
import lombok.extern.slf4j.Slf4j;

/**
 * How long a task has waited on a person (task 82880ea6, gaps §1.1): nothing measured it, so a
 * gate nobody looked at stayed a gate nobody looked at. Only boards that set humanQueueAgeMinutes
 * are read; each hold that crosses the threshold is notified once.
 */
@Slf4j
@Service
public class BoardQueueAgeService {

	@Autowired private AgentBoardService agentBoardService;
	@Autowired private AgentTaskService agentTaskService;
	@Autowired private AgentBoardNotifier agentBoardNotifier;

	/** @return how many held tasks were past their board's threshold (each notified at most once) */
	public int sweep(ZonedDateTime now) {
		int over = 0;
		for (AgentBoardData board : agentBoardService.listWatchingHumanQueueAge()) {
			Integer threshold = board.getHumanQueueAgeMinutes();
			if (null == threshold || threshold <= 0) continue;
			for (AgentTaskData td : agentTaskService.listByBoard(board.getUuid(), TaskStatus.ON_HOLD.name())) {
				Long waited = AgentBoardNotifier.waitingMinutes(td, now);
				if (null == waited || waited < threshold) continue;
				agentBoardNotifier.queueAge(board, td, waited);
				over++;
			}
		}
		return over;
	}
}
