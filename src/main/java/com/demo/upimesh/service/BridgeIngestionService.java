package com.demo.upimesh.service;

import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.model.IdempotencyRecord;
import com.demo.upimesh.model.IdempotencyRecordRepository;
import com.demo.upimesh.model.MeshPacket;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class BridgeIngestionService {
    private final HybridCryptoService crypto;
    private final PacketProcessingService processor;
    private final IdempotencyRecordRepository records;
    private final ObjectMapper mapper;

    @Value("${upi.mesh.packet-max-age-seconds:86400}") private long maxAgeSeconds;

    public BridgeIngestionService(HybridCryptoService crypto, PacketProcessingService processor,
                                  IdempotencyRecordRepository records, ObjectMapper mapper) {
        this.crypto = crypto; this.processor = processor; this.records = records; this.mapper = mapper;
    }

    public IngestResult ingest(MeshPacket packet, String bridgeNodeId, int hopCount) {
        String hash = "?";
        try {
            hash = crypto.hashCiphertext(packet.getCiphertext());
            try {
                return processor.process(packet, hash, bridgeNodeId, hopCount, maxAgeSeconds);
            } catch (DataIntegrityViolationException duplicate) {
                var record = records.findByPacketHash(hash);
                if (record.isPresent()) {
                    return mapper.readValue(record.get().getResponsePayload(), IngestResult.class);
                }
                return IngestResult.invalid(hash, "reused_nonce");
            }
        } catch (Exception e) {
            return IngestResult.invalid(hash, "internal_error");
        }
    }

    public record IngestResult(String outcome, String packetHash, String reason, Long transactionId) {
        public static IngestResult settled(String hash, com.demo.upimesh.model.Transaction tx) {
            return new IngestResult("SETTLED", hash, null, tx.getId());
        }
        public static IngestResult rejected(String hash, com.demo.upimesh.model.Transaction tx) {
            return new IngestResult("REJECTED", hash, "insufficient_balance", tx.getId());
        }
        public static IngestResult invalid(String hash, String reason) {
            return new IngestResult("INVALID", hash, reason, null);
        }
    }
}
