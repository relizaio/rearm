/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.changelog.entry;

public final class SummaryEntry extends AbstractCompositeChangeLogEntry {
    public SummaryEntry(String message, String indentation) {
        // super("* ###", message);
        super(indentation + "* ###", message);
    }
}
