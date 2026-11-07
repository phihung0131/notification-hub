package org.example.analyticsservice.application.port;

import java.util.List;

import org.example.analyticsservice.domain.MessageRecord;

public interface MessageWritePort {

    /**
     * Durably upserts the records in one transaction. Idempotent; never regresses a stored status
     * (see MessageStatusProgression). At most one record per message id per call.
     */
    void upsertAll(List<MessageRecord> records);
}
