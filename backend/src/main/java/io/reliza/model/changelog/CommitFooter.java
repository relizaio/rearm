/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.changelog;

public final class CommitFooter implements BreakingChangeItem{
    public static final CommitFooter EMPTY = new CommitFooter("");
    private final boolean isBreakingChange;
    private final String footer;

    public CommitFooter(String footer) {
        this.isBreakingChange = footer.startsWith("BREAKING-CHANGE: ");
        this.footer = isBreakingChange ? footer.substring("BREAKING-CHANGE: ".length()).trim() : footer.trim();
    }

    public String getFooter() {
        return footer;
    }

    public boolean isBreakingChange() {
        return isBreakingChange;
    }

    public String getBreakingChangeDescription() {
        return isBreakingChange ? footer : "";
    }
}
