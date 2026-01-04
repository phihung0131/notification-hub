package com.example.analyticsservice.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.example.analyticsservice.model.Message;

@Repository
public interface MessageRepository extends JpaRepository<Message, UUID> {
    List<Message> findByTenantIdOrderByCreatedAtDesc(String tenantId);
}
