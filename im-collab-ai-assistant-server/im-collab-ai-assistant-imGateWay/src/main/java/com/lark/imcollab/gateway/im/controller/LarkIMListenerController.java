package com.lark.imcollab.gateway.im.controller;

import com.lark.imcollab.gateway.im.service.LarkIMListenerService;
import com.lark.imcollab.gateway.im.service.LarkIMListenerStartRequest;
import com.lark.imcollab.gateway.im.service.LarkIMListenerStatusResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/im/listener")
public class LarkIMListenerController {

    private final LarkIMListenerService listenerService;

    public LarkIMListenerController(LarkIMListenerService listenerService) {
        this.listenerService = listenerService;
    }

    @PostMapping("/start")
    public LarkIMListenerStatusResponse start(@RequestBody LarkIMListenerStartRequest request) {
        return listenerService.start(request);
    }

    @PostMapping("/stop")
    public LarkIMListenerStatusResponse stop(@RequestBody LarkIMListenerStartRequest request) {
        return listenerService.stop(request);
    }

    @GetMapping("/status")
    public LarkIMListenerStatusResponse status(@RequestParam String profileName) {
        return listenerService.status(profileName);
    }
}
