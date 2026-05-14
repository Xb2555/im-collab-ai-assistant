package com.lark.imcollab.skills.lark.im;

@FunctionalInterface
public interface LarkMentionTargetIdentityService {

    boolean isLeadingBotMentionCommand(LarkMessageSearchItem item);
}
