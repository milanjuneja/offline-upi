package com.demo.upimesh.service;

import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

/** All durable effects for a first delivery commit or roll back together. */
@Service
public class PacketProcessingService {
    private final IdempotencyRecordRepository records;
    private final HybridCryptoService crypto;
    private final SettlementService settlement;
    private final ObjectMapper mapper;

    public PacketProcessingService(IdempotencyRecordRepository records, HybridCryptoService crypto,
                                   SettlementService settlement, ObjectMapper mapper) {
        this.records = records; this.crypto = crypto; this.settlement = settlement; this.mapper = mapper;
    }

    @Transactional
    public BridgeIngestionService.IngestResult process(MeshPacket packet, String hash,
                                                        String bridgeNodeId, int hopCount,
                                                        long maxAgeSeconds) throws Exception {
        IdempotencyRecord record = new IdempotencyRecord();
        record.setPacketHash(hash);
        record.setStatus(IdempotencyRecord.Status.REJECTED);
        record.setResponsePayload("{}");
        records.saveAndFlush(record); // unique insert arbitrates concurrent deliveries

        BridgeIngestionService.IngestResult result;
        PaymentInstruction instruction;
        try {
            instruction = crypto.decrypt(packet.getCiphertext());
        } catch (Exception e) {
            result = BridgeIngestionService.IngestResult.invalid(hash, "decryption_failed");
            record.setResponsePayload(mapper.writeValueAsString(result));
            return result;
        }

        long ageSeconds = (Instant.now().toEpochMilli() - instruction.getSignedAt()) / 1000;
        if (ageSeconds > maxAgeSeconds) result = BridgeIngestionService.IngestResult.invalid(hash, "stale_packet");
        else if (ageSeconds < -300) result = BridgeIngestionService.IngestResult.invalid(hash, "future_dated");
        else {
            Transaction tx = settlement.settle(instruction, hash, bridgeNodeId, hopCount);
            result = tx.getStatus() == Transaction.Status.SETTLED
                    ? BridgeIngestionService.IngestResult.settled(hash, tx)
                    : BridgeIngestionService.IngestResult.rejected(hash, tx);
            record.setTransactionId(tx.getId());
            record.setStatus(tx.getStatus() == Transaction.Status.SETTLED
                    ? IdempotencyRecord.Status.COMPLETED : IdempotencyRecord.Status.REJECTED);
        }
        record.setResponsePayload(mapper.writeValueAsString(result));
        return result;
    }
}
