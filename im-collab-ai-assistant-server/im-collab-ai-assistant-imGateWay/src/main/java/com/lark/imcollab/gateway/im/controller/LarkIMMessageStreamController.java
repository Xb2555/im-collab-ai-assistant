package com.lark.imcollab.gateway.im.controller;

import com.lark.imcollab.gateway.im.service.LarkIMMessageStreamService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/im")
public class LarkIMMessageStreamController {

    private final LarkIMMessageStreamService streamService;

    public LarkIMMessageStreamController(LarkIMMessageStreamService streamService) {
        this.streamService = streamService;
    }

    @GetMapping(value = "/chats/{chatId}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> streamMessages(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String chatId
    ) {
        return ResponseEntity.ok()

                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(streamService.subscribe(authorization, chatId));
    }
}
