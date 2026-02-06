package org.example.notificationservice.service;

import java.util.Optional;

import org.example.commons.exception.BaseException;
import org.example.notificationservice.common.exception.ApiErrorMessage;
import org.example.notificationservice.dto.request.CreateChannelRequest;
import org.example.notificationservice.model.Channel;
import org.example.notificationservice.repository.ChannelRepository;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ChannelService {
    private final ChannelRepository channelRepository;

    public Channel createChannel(CreateChannelRequest request) {
        validateChannel(request.getCode());
        Channel channel = new Channel();
        channel.setName(request.getName());
        channel.setCode(request.getCode());
        return channelRepository.save(channel);
    }

    /** Validate channel exists. */
    private void validateChannel(String channelCode) {
        Optional<Channel> channel = channelRepository.findByCode(channelCode);

        if (channel.isPresent()) {
            throw new BaseException(ApiErrorMessage.CHANNEL_EXISTED);
        }
    }
}
