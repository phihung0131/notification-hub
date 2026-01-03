package org.example.notificationservice.repository;

import java.util.Optional;

import org.example.notificationservice.model.Channel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ChannelRepository extends JpaRepository<Channel, String> {
    Optional<Channel> findByCode(String code);
}
