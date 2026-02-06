package org.example.analyticsservice.repository;

import java.util.List;
import java.util.UUID;

import org.example.analyticsservice.model.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MessageRepository extends JpaRepository<Message, UUID> {
    List<Message> findByTenantIdOrderByCreatedAtDesc(String tenantId);
}
