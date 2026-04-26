package com.lark.imcollab.skills.lark.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.skills.framework.cli.CliCommand;
import com.lark.imcollab.skills.framework.cli.CliStreamHandle;
import com.lark.imcollab.skills.framework.cli.CliStreamListener;
import com.lark.imcollab.skills.framework.cli.CliStreamingCommandExecutor;
import com.lark.imcollab.skills.lark.config.LarkCliProperties;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LarkMessageEventSubscriptionToolTests {

    @Test
    void shouldStartBotMessageReceiveSubscriptionForProfile() {
        StubStreamingExecutor executor = new StubStreamingExecutor();
        LarkMessageEventSubscriptionTool tool = new LarkMessageEventSubscriptionTool(
                executor,
                new LarkCliProperties(),
                new ObjectMapper()
        );

        LarkEventSubscriptionStatus status = tool.startMessageSubscription("profile-123", event -> {
        });

        assertThat(status.profileName()).isEqualTo("profile-123");
        assertThat(status.running()).isTrue();
        assertThat(executor.command.arguments())
                .containsExactly("--profile", "profile-123", "event", "+subscribe",
                        "--event-types", "im.message.receive_v1", "--as", "bot", "--quiet");
    }

    @Test
    void shouldStartBotMessageReceiveSubscriptionForDefaultProfile() {
        StubStreamingExecutor executor = new StubStreamingExecutor();
        LarkMessageEventSubscriptionTool tool = new LarkMessageEventSubscriptionTool(
                executor,
                new LarkCliProperties(),
                new ObjectMapper()
        );

        LarkEventSubscriptionStatus status = tool.startMessageSubscription(null, event -> {
        });

        assertThat(status.profileName()).isEqualTo("default");
        assertThat(status.running()).isTrue();
        assertThat(executor.command.arguments())
                .containsExactly("event", "+subscribe", "--event-types", "im.message.receive_v1", "--as", "bot",
                        "--quiet");
    }

    @Test
    void shouldAcceptPrivateMessageEvents() {
        StubStreamingExecutor executor = new StubStreamingExecutor();
        LarkMessageEventSubscriptionTool tool = new LarkMessageEventSubscriptionTool(
                executor,
                new LarkCliProperties(),
                new ObjectMapper()
        );
        List<LarkMessageEvent> events = new ArrayList<>();
        tool.startMessageSubscription("profile-123", events::add);

        executor.listener.onLine("""
                {"schema":"2.0","header":{"event_id":"evt-1","event_type":"im.message.receive_v1","create_time":"1773491924409"},"event":{"message":{"chat_id":"oc_p2p","chat_type":"p2p","content":"{\\"text\\":\\"生成周报\\"}","message_id":"om_1","message_type":"text","create_time":"1773491924409"},"sender":{"sender_id":{"open_id":"ou_1"},"sender_type":"user"}}}
                """);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).messageId()).isEqualTo("om_1");
        assertThat(events.get(0).chatType()).isEqualTo("p2p");
        assertThat(events.get(0).content()).isEqualTo("生成周报");
        assertThat(events.get(0).senderOpenId()).isEqualTo("ou_1");
    }

    @Test
    void shouldAcceptGroupMessageOnlyWhenMentionIsPresent() {
        StubStreamingExecutor executor = new StubStreamingExecutor();
        LarkMessageEventSubscriptionTool tool = new LarkMessageEventSubscriptionTool(
                executor,
                new LarkCliProperties(),
                new ObjectMapper()
        );
        List<LarkMessageEvent> events = new ArrayList<>();
        tool.startMessageSubscription("profile-123", events::add);

        executor.listener.onLine("""
                {"schema":"2.0","header":{"event_id":"evt-2","event_type":"im.message.receive_v1","create_time":"1773491924410"},"event":{"message":{"chat_id":"oc_group","chat_type":"group","content":"{\\"text\\":\\"普通群消息\\"}","message_id":"om_ignored","message_type":"text","create_time":"1773491924410"},"sender":{"sender_id":{"open_id":"ou_2"},"sender_type":"user"}}}
                """);
        executor.listener.onLine("""
                {"schema":"2.0","header":{"event_id":"evt-3","event_type":"im.message.receive_v1","create_time":"1773491924411"},"event":{"message":{"chat_id":"oc_group","chat_type":"group","content":"{\\"text\\":\\"<at user_id=\\\\\\"ou_bot\\\\\\">机器人</at> 生成方案\\"}","message_id":"om_2","message_type":"text","create_time":"1773491924411","mentions":[{"id":{"open_id":"ou_bot"},"name":"机器人"}]},"sender":{"sender_id":{"open_id":"ou_2"},"sender_type":"user"}}}
                """);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).messageId()).isEqualTo("om_2");
        assertThat(events.get(0).chatType()).isEqualTo("group");
        assertThat(events.get(0).mentionDetected()).isTrue();
    }

    @Test
    void shouldStopExistingSubscription() {
        StubStreamingExecutor executor = new StubStreamingExecutor();
        LarkMessageEventSubscriptionTool tool = new LarkMessageEventSubscriptionTool(
                executor,
                new LarkCliProperties(),
                new ObjectMapper()
        );
        tool.startMessageSubscription("profile-123", event -> {
        });

        LarkEventSubscriptionStatus status = tool.stopMessageSubscription("profile-123");

        assertThat(executor.handle.stopped).isTrue();
        assertThat(status.running()).isFalse();
    }

    @Test
    void shouldStopAllSubscriptions() {
        StubStreamingExecutor executor = new StubStreamingExecutor();
        LarkMessageEventSubscriptionTool tool = new LarkMessageEventSubscriptionTool(
                executor,
                new LarkCliProperties(),
                new ObjectMapper()
        );
        tool.startMessageSubscription("profile-123", event -> {
        });
        tool.startMessageSubscription("profile-456", event -> {
        });

        tool.stopAllMessageSubscriptions();

        assertThat(executor.handles).allSatisfy(handle -> assertThat(handle.stopped).isTrue());
        assertThat(tool.getMessageSubscriptionStatus("profile-123").running()).isFalse();
        assertThat(tool.getMessageSubscriptionStatus("profile-456").running()).isFalse();
    }

    private static final class StubStreamingExecutor implements CliStreamingCommandExecutor {

        private final List<StubStreamHandle> handles = new ArrayList<>();
        private StubStreamHandle handle;
        private CliCommand command;
        private CliStreamListener listener;

        @Override
        public CliStreamHandle start(CliCommand command, CliStreamListener listener) throws IOException {
            this.handle = new StubStreamHandle();
            this.handles.add(handle);
            this.command = command;
            this.listener = listener;
            return handle;
        }
    }

    private static final class StubStreamHandle implements CliStreamHandle {

        private boolean stopped;

        @Override
        public boolean isRunning() {
            return !stopped;
        }

        @Override
        public void stop() {
            stopped = true;
        }
    }
}
