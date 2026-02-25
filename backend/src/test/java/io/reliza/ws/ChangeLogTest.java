/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.model.changelog.CommitMessage;
import lombok.extern.slf4j.Slf4j;

/**
 * Unit test related to Component functionality
 */
@Slf4j
@ExtendWith(SpringExtension.class)
@SpringBootTest
public class ChangeLogTest {

	@Test
	public void commitRegexJira() {
		String commitMsg = "fix: add arguments for nginx-entry command (RLZ-123)";
		var cm = new CommitMessage(commitMsg);
		Assertions.assertEquals("RLZ-123", cm.getTicket());
	}
	
	@Test
	public void commitRegexJiraBySomebody() {
		String commitMsg = "fix: add arguments for nginx-entry command (RLZ-123), by [Joe Bean](mailto:joe@reliza.io)";
		var cm = new CommitMessage(commitMsg);
		Assertions.assertEquals("RLZ-123", cm.getTicket());
		Assertions.assertEquals("add arguments for nginx-entry command, by [Joe Bean](mailto:joe@reliza.io)", cm.getMessage());
	}

}
