package com.lark.imcollab.skills.lark.im;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DefaultLarkMentionTargetIdentityService implements LarkMentionTargetIdentityService {
    private static final Logger log = LoggerFactory.getLogger(DefaultLarkMentionTargetIdentityService.class);

    @Override
    public boolean isLeadingBotMentionCommand(LarkMessageSearchItem item) {
        if (item == null || item.mentions() == null || item.mentions().isEmpty()) {
            return false;
        }
        for (LarkMessageMention mention : item.mentions()) {
            if (mention != null && isBotMention(mention)) {
                log.info("LARK_IM_MENTION_BOT_HIT messageId={} mentionId={} mentionName='{}' content='{}'",
                        safe(item.messageId()),
                        safe(mention.id()),
                        safe(mention.name()),
                        truncate(item.content(), 200));
                return true;
            }
        }
        log.info("LARK_IM_MENTION_BOT_MISS messageId={} mentionIds={} mentionNames={} content='{}'",
                safe(item.messageId()),
                item.mentions().stream().map(mention -> safe(mention.id())).toList(),
                item.mentions().stream().map(mention -> safe(mention.name())).toList(),
                truncate(item.content(), 200));
        return false;
    }

    private boolean isBotMention(LarkMessageMention mention) {
        String mentionId = normalize(mention.id());
        if (mentionId == null) {
            return false;
        }
        if (mentionId.startsWith("cli_")) {
            log.info("LARK_IM_MENTION_IDENTITY mentionId={} idType={} outcome=cli-bot-filter mentionName='{}'",
                    mentionId,
                    safe(mention.idType()),
                    safe(mention.name()));
            return true;
        }
        log.info("LARK_IM_MENTION_IDENTITY mentionId={} idType={} outcome=non-cli-skip mentionName='{}'",
                mentionId,
                safe(mention.idType()),
                safe(mention.name()));
        return false;
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(0, maxChars - 3)) + "...";
    }
}
