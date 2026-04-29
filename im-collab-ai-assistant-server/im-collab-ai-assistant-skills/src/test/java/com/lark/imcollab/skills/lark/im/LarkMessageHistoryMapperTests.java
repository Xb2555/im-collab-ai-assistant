package com.lark.imcollab.skills.lark.im;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LarkMessageHistoryMapperTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldMapLarkMessageHistoryData() throws Exception {
        JsonNode data = objectMapper.readTree("""
                {
                  "has_more": true,
                  "page_token": "next-page",
                  "items": [
                    {
                      "message_id": "om_1",
                      "root_id": "om_root",
                      "parent_id": "om_parent",
                      "thread_id": "omt_1",
                      "msg_type": "text",
                      "create_time": "1615380573411",
                      "update_time": "1615380573412",
                      "deleted": false,
                      "updated": true,
                      "chat_id": "oc_1",
                      "sender": {
                        "id": "ou_1",
                        "id_type": "open_id",
                        "sender_type": "user",
                        "tenant_key": "tenant_1"
                      },
                      "body": {
                        "content": "{\\"text\\":\\"hello\\"}"
                      },
                      "mentions": [
                        {
                          "key": "@_user_1",
                          "id": "ou_2",
                          "id_type": "open_id",
                          "name": "Tom",
                          "tenant_key": "tenant_1"
                        }
                      ],
                      "upper_message_id": "om_upper"
                    }
                  ]
                }
                """);

        LarkMessageHistoryResponse response = LarkMessageHistoryMapper.fromData(data);

        assertThat(response.hasMore()).isTrue();
        assertThat(response.pageToken()).isEqualTo("next-page");
        assertThat(response.items()).hasSize(1);
        LarkMessageHistoryItem item = response.items().get(0);
        assertThat(item.messageId()).isEqualTo("om_1");
        assertThat(item.senderId()).isEqualTo("ou_1");
        assertThat(item.content()).isEqualTo("{\"text\":\"hello\"}");
        assertThat(item.mentions()).containsExactly(new LarkMessageMention(
                "@_user_1",
                "ou_2",
                "open_id",
                "Tom",
                "tenant_1"
        ));
    }
}
