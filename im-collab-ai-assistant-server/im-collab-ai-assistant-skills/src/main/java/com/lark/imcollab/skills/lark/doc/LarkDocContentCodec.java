package com.lark.imcollab.skills.lark.doc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.skills.lark.config.LarkDocProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class LarkDocContentCodec {

    private final LarkDocOpenApiClient openApiClient;
    private final LarkDocProperties docProperties;
    private final ObjectMapper objectMapper;

    public LarkDocContentCodec(LarkDocOpenApiClient openApiClient, LarkDocProperties docProperties, ObjectMapper objectMapper) {
        this.openApiClient = openApiClient;
        this.docProperties = docProperties;
        this.objectMapper = objectMapper;
    }

    public void writeMarkdownBlocks(String documentId, String markdown) {
        ConvertedMarkdownBlocks converted = convertMarkdownToBlocks(documentId, markdown);
        List<Map<String, Object>> blocks = sanitizeConvertedBlocks(converted.blocks());
        if (blocks.isEmpty()) return;
        int batchSize = Math.min(1000, Math.max(1, docProperties == null ? 40 : docProperties.getMaxBlocksPerRequest()));
        List<ConvertedMarkdownBlockBatch> batches = splitIntoBatches(converted.firstLevelBlockIds(), blocks, batchSize);
        for (ConvertedMarkdownBlockBatch batch : batches) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("children_id", batch.childrenIds());
            body.put("descendants", batch.descendants());
            body.put("index", -1);
            openApiClient.post(
                    "/open-apis/docx/v1/documents/" + documentId + "/blocks/" + documentId
                            + "/descendant?client_token=" + UUID.randomUUID(),
                    body, timeoutSeconds()
            );
            pauseBetweenWrites();
        }
    }

    private ConvertedMarkdownBlocks convertMarkdownToBlocks(String documentId, String markdown) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content_type", "markdown");
        body.put("content", markdown);
        JsonNode data = openApiClient.post("/open-apis/docx/v1/documents/blocks/convert", body, timeoutSeconds());
        JsonNode blocksNode = firstExisting(data, "blocks", "descendants");
        JsonNode firstLevelNode = firstExisting(data, "first_level_block_ids", "children_id", "children_ids");
        List<Map<String, Object>> blocks = blocksNode != null && blocksNode.isArray()
                ? objectMapper.convertValue(blocksNode, new TypeReference<>() {}) : List.of();
        List<String> firstLevelBlockIds = firstLevelNode != null && firstLevelNode.isArray()
                ? objectMapper.convertValue(firstLevelNode, new TypeReference<>() {}) : List.of();
        return new ConvertedMarkdownBlocks(blocks, firstLevelBlockIds);
    }

    private List<Map<String, Object>> sanitizeConvertedBlocks(List<Map<String, Object>> blocks) {
        if (blocks == null || blocks.isEmpty()) return List.of();
        List<Map<String, Object>> sanitized = new ArrayList<>(blocks.size());
        for (Map<String, Object> block : blocks) {
            Map<String, Object> copy = objectMapper.convertValue(block, new TypeReference<>() {});
            removeReadOnlyMergeInfo(copy);
            sanitized.add(copy);
        }
        return sanitized;
    }

    @SuppressWarnings("unchecked")
    private void removeReadOnlyMergeInfo(Object value) {
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = (Map<String, Object>) rawMap;
            map.remove("merge_info");
            for (Object child : new ArrayList<>(map.values())) removeReadOnlyMergeInfo(child);
        } else if (value instanceof List<?> list) {
            for (Object child : list) removeReadOnlyMergeInfo(child);
        }
    }

    private List<ConvertedMarkdownBlockBatch> splitIntoBatches(List<String> preferredRootIds, List<Map<String, Object>> blocks, int batchSize) {
        Map<String, Map<String, Object>> blocksById = new LinkedHashMap<>();
        for (Map<String, Object> block : blocks) {
            String id = stringValue(block.get("block_id"));
            if (hasText(id)) blocksById.put(id, block);
        }
        List<String> rootIds = firstLevelBlockIds(preferredRootIds, blocks);
        if (rootIds.isEmpty()) return List.of(new ConvertedMarkdownBlockBatch(List.of(), blocks));
        List<ConvertedMarkdownBlockBatch> batches = new ArrayList<>();
        List<String> currentRootIds = new ArrayList<>();
        LinkedHashSet<String> currentBlockIds = new LinkedHashSet<>();
        for (String rootId : rootIds) {
            LinkedHashSet<String> subtreeIds = collectSubtreeIds(rootId, blocksById);
            if (subtreeIds.isEmpty()) continue;
            if (!currentBlockIds.isEmpty() && currentBlockIds.size() + subtreeIds.size() > batchSize) {
                batches.add(toBatch(currentRootIds, currentBlockIds, blocksById));
                currentRootIds = new ArrayList<>();
                currentBlockIds = new LinkedHashSet<>();
            }
            currentRootIds.add(rootId);
            currentBlockIds.addAll(subtreeIds);
        }
        if (!currentBlockIds.isEmpty()) batches.add(toBatch(currentRootIds, currentBlockIds, blocksById));
        return batches;
    }

    private LinkedHashSet<String> collectSubtreeIds(String rootId, Map<String, Map<String, Object>> blocksById) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        collectSubtreeIds(rootId, blocksById, ids);
        return ids;
    }

    private void collectSubtreeIds(String blockId, Map<String, Map<String, Object>> blocksById, Set<String> ids) {
        if (!hasText(blockId) || ids.contains(blockId)) return;
        Map<String, Object> block = blocksById.get(blockId);
        if (block == null) return;
        ids.add(blockId);
        for (String childId : childIds(block)) collectSubtreeIds(childId, blocksById, ids);
    }

    private ConvertedMarkdownBlockBatch toBatch(List<String> rootIds, LinkedHashSet<String> blockIds, Map<String, Map<String, Object>> blocksById) {
        List<Map<String, Object>> descendants = blockIds.stream().map(blocksById::get).filter(b -> b != null).toList();
        return new ConvertedMarkdownBlockBatch(List.copyOf(rootIds), descendants);
    }

    private List<String> firstLevelBlockIds(List<String> preferredIds, List<Map<String, Object>> descendants) {
        if (preferredIds != null && !preferredIds.isEmpty()) {
            Set<String> descendantIds = descendants == null ? Set.of()
                    : descendants.stream().map(b -> stringValue(b.get("block_id"))).filter(this::hasText)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            return preferredIds.stream().filter(this::hasText)
                    .filter(id -> descendantIds.isEmpty() || descendantIds.contains(id)).toList();
        }
        if (descendants == null || descendants.isEmpty()) return List.of();
        Set<String> childIds = descendants.stream().flatMap(b -> childIds(b).stream()).collect(Collectors.toSet());
        return descendants.stream().map(b -> stringValue(b.get("block_id"))).filter(this::hasText)
                .filter(id -> !childIds.contains(id)).toList();
    }

    @SuppressWarnings("unchecked")
    private List<String> childIds(Map<String, Object> block) {
        Object children = block == null ? null : block.get("children");
        if (!(children instanceof List<?> list)) return List.of();
        return list.stream().map(this::stringValue).filter(this::hasText).toList();
    }

    private JsonNode firstExisting(JsonNode node, String... names) {
        if (node == null || names == null) return null;
        for (String name : names) {
            JsonNode v = node.path(name);
            if (!v.isMissingNode() && !v.isNull()) return v;
        }
        return null;
    }

    private String stringValue(Object value) { return value == null ? null : String.valueOf(value); }
    private boolean hasText(String value) { return value != null && !value.isBlank(); }
    private int timeoutSeconds() { return docProperties == null || docProperties.getRequestTimeoutSeconds() <= 0 ? 30 : docProperties.getRequestTimeoutSeconds(); }
    private void pauseBetweenWrites() {
        try { Thread.sleep(380L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("飞书文档写入被中断。", e); }
    }

    private record ConvertedMarkdownBlocks(List<Map<String, Object>> blocks, List<String> firstLevelBlockIds) {}
    private record ConvertedMarkdownBlockBatch(List<String> childrenIds, List<Map<String, Object>> descendants) {}
}
