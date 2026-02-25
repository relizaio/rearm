/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.common;

import java.util.Arrays;
import java.util.regex.Pattern;

import io.reliza.model.changelog.CommitType;

import static java.util.stream.Collectors.joining;

public final class CommitMatcherUtil {
    public static final Pattern COMMIT_MESSAGE_REGEX = createRegexPattern();
    public static final Pattern JIRA_REGEX = createJiraRegexPattern();
    public static final String LINE_SEPARATOR = "\n\n";
    private static final String JIRA_PATTERN = "((?<!([A-Z0-9]{1,10})-?)[A-Z][A-Z0-9]+-[1-9][0-9]*)";
    private static final String JIRA_SUFFIX = "([(]" + JIRA_PATTERN +"[)])?(,\\sby\\s.*)?$";
   

    private CommitMatcherUtil() {
    }

    private static Pattern createRegexPattern() {
        // i.e. ^(build|test|chore|feat|fix|docs)
        String typePrefix = Arrays.stream(CommitType.values()).map(CommitType::getPrefix).collect(joining("|", "^(", ")"));
        return Pattern.compile(typePrefix + "[(]?([\\w\\-]+)?[)]?(!)?:\\s(.+?)" + JIRA_SUFFIX);
    }

    private static Pattern createJiraRegexPattern() {
        return Pattern.compile(JIRA_PATTERN);
    }
}
