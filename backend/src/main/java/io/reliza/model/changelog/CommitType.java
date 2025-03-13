/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.changelog;

public enum CommitType {
    BUG_FIX(0, "fix", "Bug Fixes"),
    FEAT(1, "feat", "Features"),
    PERFORMANCE(2, "perf", "Performance Improvements"),
    REVERT(3, "revert", "Reverts"),
    REFACTOR(4, "refactor", "Code Refactoring"),
    BUILD(5, "build", "Builds"),
    TEST(6, "test", "Tests"),
    DOCS(7, "docs", "Documentation"),
    CHORE(8, "chore", "Chores"),
    CI(9, "ci", "Continuous Integration"),
    STYLE(10, "style", "Styles"),
    OTHERS(11, "other", "Others");

  
    private final int displayPriority;
    private final String prefix;
    private final String fullName;
  
    CommitType(int displayPriority, String prefix, String fullName) {
        this.displayPriority = displayPriority;
        this.prefix = prefix;
        this.fullName = fullName;
    }
  
    public int getDisplayPriority() {
        return displayPriority;
    }
  
    public String getFullName() {
        return fullName;
    }
  
    public String getPrefix() {
        return prefix;
    }
  
    public static CommitType of(String value) {
        for (CommitType type : CommitType.values()) {
            if (type.prefix.equals(value)) {
                return type;
            }
        }
        throw new IllegalStateException(
            value + " commit type is not supported by " + CommitType.class.getSimpleName());
    }
}
  
