package com.lark.imcollab.harness.document.iteration.support;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RichContentExecutionContext {

    private final Map<String, Object> artifacts = new HashMap<>();
    private final List<String> createdBlockIds = new ArrayList<>();
    private final List<String> createdAssetRefs = new ArrayList<>();
    private long beforeRevision = -1;
    private long afterRevision = -1;

    public void put(String key, Object value) {
        artifacts.put(key, value);
    }

    public Object get(String key) {
        return artifacts.get(key);
    }

    public String getString(String key) {
        Object v = artifacts.get(key);
        return v == null ? null : String.valueOf(v);
    }

    public void addCreatedBlockId(String blockId) {
        if (blockId != null && !blockId.isBlank()) {
            createdBlockIds.add(blockId);
        }
    }

    public void addCreatedAssetRef(String ref) {
        if (ref != null && !ref.isBlank()) {
            createdAssetRefs.add(ref);
        }
    }

    public List<String> getCreatedBlockIds() {
        return createdBlockIds;
    }

    public List<String> getCreatedAssetRefs() {
        return createdAssetRefs;
    }

    public long getBeforeRevision() {
        return beforeRevision;
    }

    public void setBeforeRevision(long beforeRevision) {
        this.beforeRevision = beforeRevision;
    }

    public long getAfterRevision() {
        return afterRevision;
    }

    public void setAfterRevision(long afterRevision) {
        this.afterRevision = afterRevision;
    }
}
