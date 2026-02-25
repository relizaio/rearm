/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.changelog.entry;

import java.util.ArrayList;
import java.util.List;

public final class ReleaseEntry extends AbstractCompositeChangeLogEntry {
    public ReleaseEntry(String header, String message) {
        super(header, message);
    }

    public static class Builder {
        protected List<ChangeLogEntry> children = new ArrayList<>();
        protected String header;
        protected String message;

        public Builder addHeader(String header) {
            this.header = header;
            return this;
        }

        public Builder addChild(ChangeLogEntry entry) {
            children.add(entry);
            return this;
        }

        public Builder addMessage(String message) {
            this.message = message;
            return this;
        }

        public ReleaseEntry build() {
            ReleaseEntry releaseEntry = new ReleaseEntry(header, message);
            releaseEntry.addAll(this.children);
            return releaseEntry;
        }
    }
}