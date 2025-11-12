package org.example.notificationservice.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.notificationservice.common.baseclass.ApiResponse;
import org.example.notificationservice.dto.request.CreateChannelRequest;
import org.example.notificationservice.model.Channel;
import org.example.notificationservice.service.ChannelService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/channels")
public class ChannelController {

    private final ChannelService channelService;

    @PostMapping
    public ResponseEntity<ApiResponse<Channel>> createChannel(
            @Valid @RequestBody CreateChannelRequest request
    ) {
        Channel newChannel = channelService.createChannel(request);
        return ResponseEntity.ok(ApiResponse.ok(newChannel));
    }

}
