/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.changelog.entry;

import java.util.List;

public interface CompositeChangeLogEntry {
    List<ChangeLogEntry> getChildren();
}
