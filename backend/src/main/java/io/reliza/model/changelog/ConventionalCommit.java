/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.changelog;

import java.net.URI;
import java.util.stream.Stream;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class ConventionalCommit {
    private final CommitMessage commitMessage;
    private final CommitBody commitBody;
    private final CommitFooter commitFooter;

    public ConventionalCommit(CommitMessage commitMessage, CommitBody commitBody, CommitFooter commitFooter) {
        this.commitMessage = commitMessage;
        this.commitBody = commitBody;
        this.commitFooter = commitFooter;
    }

    public ConventionalCommit(CommitMessage commitMessage) {
        this(commitMessage, CommitBody.EMPTY, CommitFooter.EMPTY);
    }

    public ConventionalCommit(CommitMessage commitMessage, CommitFooter commitFooter) {
        this(commitMessage, CommitBody.EMPTY, commitFooter);
    }

    public String getFooter() {
        return commitFooter.getFooter();
    }

    public String getBody() {
        return commitBody.getBody();
    }

    public String getRawMessage() {
        return commitMessage.getRawMessage();
    }

    public CommitType getType() {
        return commitMessage.getType();
    }

    public String getDecoratedScope() {
        return "**" + getScope() + "**";
    }

    public String getMessage() {
        return commitMessage.getMessage();
    }

    public String getScope() {
        return commitMessage.getScope();
    }

    public boolean isBreakingChange() {
        return commitMessage.isBreakingChange()
            || commitBody.isBreakingChange()
            || commitFooter.isBreakingChange();
    }

    public String getTicket() {
        return commitMessage.getTicket();
    }

    public String getDecoratedTicket(URI ticketUri) {
    	String decTicket = null;
    	if (null == ticketUri) {
    		decTicket = "**[" + getTicket() + "]**";
    	} else {
    		decTicket = "**[" + getTicket() + "](" + ticketUri + ")**";
    	}
    	log.info("ticket uri = " + ticketUri + ", ticket = " + decTicket);
        return  decTicket;
    }

    public String getBreakingChangeDescription() {
        return Stream.of(commitFooter, commitBody, commitMessage)
            .filter(BreakingChangeItem::isBreakingChange)
            .map(BreakingChangeItem::getBreakingChangeDescription)
            .map(string -> string.replace("BREAKING-CHANGE: ", ""))
            .map(string -> string.replace("BREAKING CHANGE: ", ""))
            .findFirst()
            .orElse("");
    }
}

