/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import io.reliza.common.CommitMatcherUtil;
import io.reliza.model.changelog.ConventionalCommit;
import lombok.extern.slf4j.Slf4j;

import static io.reliza.common.Utils.drop;
import static io.reliza.common.Utils.first;
import static io.reliza.common.Utils.last;

import io.reliza.model.changelog.CommitMessage;
import io.reliza.model.changelog.CommitType;
import io.reliza.model.changelog.CommitFooter;
import io.reliza.model.changelog.CommitBody;

@Slf4j
@Service
public class ChangeLogService {

	public ConventionalCommit resolveConventionalCommit(String commit) {
		ConventionalCommit conventionalCommit = null;
		if(StringUtils.isNotEmpty(commit)){
			if(!StringUtils.isEmpty(commit)){
				String[] commitMessageArray = commit.split(CommitMatcherUtil.LINE_SEPARATOR);
				
				// var matcher = COMMIT_MESSAGE_REGEX.pattern();
				if (commitMessageArray.length >= 1
						// && first(commitMessageArray).trim().matches(matcher)) {
						){

					if (commitMessageArray.length == 1) {
						conventionalCommit = new ConventionalCommit(new CommitMessage(first(commitMessageArray)));
					} else if (commitMessageArray.length == 2) {
						conventionalCommit = new ConventionalCommit(new CommitMessage(first(commitMessageArray)),
								new CommitFooter(last(commitMessageArray)));
					} else {
						conventionalCommit = new ConventionalCommit(new CommitMessage(first(commitMessageArray)),
								new CommitBody(drop(1, 1, commitMessageArray)),
								new CommitFooter(last(commitMessageArray)));
					}
				}
			}
		}

		return conventionalCommit;
	}

	public Map<CommitType, Set<ConventionalCommit>> groupByCommitType(List<ConventionalCommit> conventionalCommits) {
		Map<CommitType, Set<ConventionalCommit>> groupByType = conventionalCommits.stream()
				.collect(Collectors.groupingBy(ConventionalCommit::getType, Collectors.toSet()));

		TreeMap<CommitType, Set<ConventionalCommit>> sortedMap = new TreeMap<>(
				Comparator.comparingInt(CommitType::getDisplayPriority));
		sortedMap.putAll(groupByType);

		return sortedMap;
	}
}
