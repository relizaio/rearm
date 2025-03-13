/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.changelog;

import java.util.regex.Matcher;

import lombok.extern.slf4j.Slf4j;

import static io.reliza.common.CommitMatcherUtil.COMMIT_MESSAGE_REGEX;
import static io.reliza.common.CommitMatcherUtil.JIRA_REGEX;
import static io.reliza.common.Utils.getNullable;

@Slf4j
public final class CommitMessage implements BreakingChangeItem{
    private final String rawMessage;
    private final CommitType type;
    private final String message;
    private final String scope;
    private final boolean isBreakingChange;
    private final String ticket;
  
    public CommitMessage(String rawMessage) {
        this.rawMessage = rawMessage.trim();
        ConventionalCommitMatcher matcher = new ConventionalCommitMatcher(this.rawMessage);
        this.type = matcher.getType();
        this.message = matcher.getMessage();
        this.scope = matcher.getScope();
        this.isBreakingChange = matcher.isBreakingChange();
        this.ticket = matcher.getTicket();
    }
  
    public String getRawMessage() {
        return rawMessage;
    }
  
    public CommitType getType() {
        return type;
    }
  
    public String getMessage() {
        return message;
    }
  
    public String getScope() {
        return scope;
    }
  
    public boolean isBreakingChange() {
        return isBreakingChange;
    }
  
    public String getBreakingChangeDescription() {
        return isBreakingChange ? message : "";
    }

    public String getTicket() {
        return ticket;
    }
  
    private static class ConventionalCommitMatcher {
        private final Matcher matcher;
        private final boolean conventional;
        private final String rawString;
    
        public ConventionalCommitMatcher(String rawString) {
            this.rawString = rawString;
            this.matcher = COMMIT_MESSAGE_REGEX.matcher(rawString);
            this.conventional = this.matcher.find();
        }
    
        private CommitType getType() {
            if(this.conventional)
                return CommitType.of(matcher.group(1));
            return CommitType.OTHERS;
        }
    
        private String getScope() {
            if(this.conventional)
                return getNullable(matcher.group(2));
            return "";
        }
    
        private boolean isBreakingChange() {
            if(this.conventional)
                return matcher.group(3) != null;
            return false;
        }
    
        private String getMessage() {
        	String msg = null;
            if(this.conventional) {
            	msg = getNullable(matcher.group(4));
            	if (matcher.groupCount() > 7) msg = msg.strip() + getNullable(matcher.group(8));
            } else {
            	msg = this.rawString;
            }
            return msg;
        }

        private String getTicket() {
            if(this.conventional)
                return getNullable(matcher.group(6));

            Matcher ticketMatcher = JIRA_REGEX.matcher(this.rawString);
            boolean hasTicket = ticketMatcher.find();
            if(hasTicket)
                return ticketMatcher.group();

            return "";
        }
    }
}
