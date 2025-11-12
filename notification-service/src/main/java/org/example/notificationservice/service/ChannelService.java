package org.example.notificationservice.service;

import lombok.RequiredArgsConstructor;
import org.example.notificationservice.dto.request.CreateChannelRequest;
import org.example.notificationservice.model.Channel;
import org.example.notificationservice.repository.ChannelRepository;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ChannelService {
    private final ChannelRepository channelRepository;

    public Channel createChannel(CreateChannelRequest request) {
        Channel channel = new Channel();
        channel.setName(request.getName());
        channel.setCode(request.getCode());
        return channelRepository.save(channel);
    }
}
