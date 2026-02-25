/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.changelog.entry;

public abstract class ChangeLogEntry {
    protected final String header;
    protected final String message;

    public ChangeLogEntry(String header, String message) {
        this.header = header;
        this.message = message;
    }

    public String toString() {
        return header + " " + message;
    }

    public String getHeader() {
        return header;
    }

    public String getMessage() {
        return message;
    }
}
