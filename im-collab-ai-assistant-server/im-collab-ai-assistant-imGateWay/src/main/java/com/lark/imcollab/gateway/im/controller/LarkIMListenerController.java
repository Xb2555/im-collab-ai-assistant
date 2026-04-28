package com.lark.imcollab.gateway.im.controller;

import com.lark.imcollab.gateway.im.service.LarkIMListenerService;
import com.lark.imcollab.gateway.im.service.LarkIMListenerStatusResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/im/listener")
public class LarkIMListenerController {

    private final LarkIMListenerService listenerService;

    public LarkIMListenerController(LarkIMListenerService listenerService) {
        this.listenerService = listenerService;
    }

    @PostMapping("/start")
    public LarkIMListenerStatusResponse start() {
        return listenerService.start();
    }

    @PostMapping("/stop")
    public LarkIMListenerStatusResponse stop() {
        return listenerService.stop();
    }

    @GetMapping("/status")
    public LarkIMListenerStatusResponse status() {
        return listenerService.status();
    }
}
