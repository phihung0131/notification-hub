package org.example.tenantservice.repository;

import java.time.Instant;

import org.example.tenantservice.model.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {

    /**
     * Check if event has been processed
     *
     * @param messageId message ID
     * @return true if exists
     */
    boolean existsByMessageId(String messageId);

    /**
     * Delete old processed events (cleanup job)
     *
     * @param before delete events older than this timestamp
     */
    @Transactional
    @Modifying
    @Query("DELETE FROM ProcessedEvent p WHERE p.processedAt < :before")
    void deleteOlderThan(Instant before);
}
