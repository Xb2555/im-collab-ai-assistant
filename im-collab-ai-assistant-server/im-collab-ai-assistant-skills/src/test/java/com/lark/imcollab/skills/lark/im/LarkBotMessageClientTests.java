package com.lark.imcollab.skills.lark.im;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.skills.lark.config.LarkBotMessageProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class LarkBotMessageClientTests {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldReuseCachedTenantAccessTokenAcrossMessages() throws IOException {
        AtomicInteger tokenRequests = new AtomicInteger();
        AtomicInteger messageRequests = new AtomicInteger();

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/open-apis/auth/v3/tenant_access_token/internal", exchange -> {
            tokenRequests.incrementAndGet();
            writeJson(exchange, """
                    {"code":0,"msg":"success","tenant_access_token":"tenant-token-1","expire":7200}
                    """);
        });
        server.createContext("/open-apis/im/v1/messages", exchange -> {
            messageRequests.incrementAndGet();
            assertThat(exchange.getRequestURI().getQuery()).isEqualTo("receive_id_type=open_id");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer tenant-token-1");
            writeJson(exchange, """
                    {"code":0,"msg":"success","data":{"message_id":"om_1","chat_id":"oc_1","create_time":"1773491924411"}}
                    """);
        });
        server.start();

        LarkBotMessageProperties properties = new LarkBotMessageProperties();
        properties.setAppId("app-id");
        properties.setAppSecret("app-secret");
        properties.setOpenApiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());

        LarkBotMessageClient client = new LarkBotMessageClient(properties, new ObjectMapper());

        client.sendTextToOpenId("ou_1", "first");
        client.sendTextToOpenId("ou_1", "second");

        assertThat(tokenRequests.get()).isEqualTo(1);
        assertThat(messageRequests.get()).isEqualTo(2);
    }

    private void writeJson(HttpExchange exchange, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
