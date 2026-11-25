package com.demo.upimesh;

import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.crypto.ServerKeyHolder;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.PaymentInstruction;
import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.service.BridgeIngestionService;
import com.demo.upimesh.service.DemoService;
import com.demo.upimesh.model.IdempotencyRecordRepository;
import com.demo.upimesh.model.TransactionRepository;
import com.demo.upimesh.model.PaymentNonceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The killer test: simulates the "three bridges deliver at the same instant"
 * scenario the user explicitly cared about.
 */
@SpringBootTest
@AutoConfigureMockMvc
class IdempotencyConcurrencyTest {

    @TempDir Path temporaryKeyDirectory;

    @Autowired private DemoService demoService;
    @Autowired private BridgeIngestionService bridge;
    @Autowired private IdempotencyRecordRepository idempotencyRecords;
    @Autowired private TransactionRepository transactions;
    @Autowired private PaymentNonceRepository paymentNonces;
    @Autowired private AccountRepository accounts;
    @Autowired private HybridCryptoService crypto;
    @Autowired private ServerKeyHolder serverKey;
    @Autowired private MockMvc mockMvc;

    @BeforeEach
    void clear() {
        idempotencyRecords.deleteAll();
        transactions.deleteAll();
        paymentNonces.deleteAll();
    }

    @Test
    void singlePacketDeliveredByThreeBridgesSettlesExactlyOnce() throws Exception {
        // Capture starting balances
        BigDecimal aliceBefore = accounts.findById("alice@demo").orElseThrow().getBalance();
        BigDecimal bobBefore = accounts.findById("bob@demo").orElseThrow().getBalance();

        // One packet, but we'll deliver it from 3 "bridges" simultaneously
        MeshPacket packet = demoService.createPacket(
                "alice@demo", "bob@demo", new BigDecimal("100.00"), "1234", 5);

        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        java.util.List<BridgeIngestionService.IngestResult> results = new CopyOnWriteArrayList<>();

        Future<?>[] futures = new Future[3];
        for (int i = 0; i < 3; i++) {
            final String node = "bridge-" + i;
            futures[i] = pool.submit(() -> {
                try {
                    start.await();
                    BridgeIngestionService.IngestResult r = bridge.ingest(packet, node, 3);
                    results.add(r);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
        }

        start.countDown(); // release all 3 threads at once
        for (Future<?> f : futures) f.get(5, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(3, results.size());
        assertTrue(results.stream().allMatch(r -> "SETTLED".equals(r.outcome())), "duplicates replay the original response");
        assertEquals(1, results.stream().map(BridgeIngestionService.IngestResult::transactionId).distinct().count());

        // Balance moved exactly once
        BigDecimal aliceAfter = accounts.findById("alice@demo").orElseThrow().getBalance();
        BigDecimal bobAfter = accounts.findById("bob@demo").orElseThrow().getBalance();
        assertEquals(aliceBefore.subtract(new BigDecimal("100.00")), aliceAfter);
        assertEquals(bobBefore.add(new BigDecimal("100.00")), bobAfter);
    }

    @Test
    void duplicateDeliveryReplaysOriginalResult() throws Exception {
        MeshPacket packet = demoService.createPacket("alice@demo", "bob@demo", new BigDecimal("12.00"), "1234", 5);
        var first = bridge.ingest(packet, "bridge-a", 1);
        var retry = bridge.ingest(packet, "bridge-b", 2);
        assertEquals(first, retry);
    }

    @Test
    void reusedNonceIsRejectedEvenWhenCiphertextChanges() throws Exception {
        String nonce = "reused-nonce";
        PaymentInstruction instruction = new PaymentInstruction(
                "alice@demo", "bob@demo", new BigDecimal("10.00"), "pin", nonce, System.currentTimeMillis());
        MeshPacket first = packetFor(instruction);
        MeshPacket second = packetFor(instruction);

        var accepted = bridge.ingest(first, "bridge-a", 1);
        var replay = bridge.ingest(second, "bridge-b", 1);

        assertEquals("SETTLED", accepted.outcome());
        assertEquals("INVALID", replay.outcome());
        assertEquals("reused_nonce", replay.reason());
        assertEquals(1, transactions.count());
    }

    @Test
    void selfTransferIsRejectedBeforeSettlement() throws Exception {
        PaymentInstruction instruction = new PaymentInstruction(
                "alice@demo", "alice@demo", new BigDecimal("10.00"), "pin", "self-transfer", System.currentTimeMillis());

        var result = bridge.ingest(packetFor(instruction), "bridge-a", 1);

        assertEquals("INVALID", result.outcome());
        assertEquals("self_transfer", result.reason());
        assertEquals(0, transactions.count());
        assertFalse(paymentNonces.existsByNonce("self-transfer"));
    }

    @Test
    void amountWithMoreThanTwoDecimalPlacesIsRejectedBeforeSettlement() throws Exception {
        PaymentInstruction instruction = new PaymentInstruction(
                "alice@demo", "bob@demo", new BigDecimal("10.001"), "pin", "invalid-amount", System.currentTimeMillis());

        var result = bridge.ingest(packetFor(instruction), "bridge-a", 1);

        assertEquals("INVALID", result.outcome());
        assertEquals("invalid_amount", result.reason());
        assertEquals(0, transactions.count());
        assertFalse(paymentNonces.existsByNonce("invalid-amount"));
    }

    @Test
    void expiredPacketIsRejectedBeforeSettlement() throws Exception {
        PaymentInstruction instruction = new PaymentInstruction(
                "alice@demo", "bob@demo", new BigDecimal("10.00"), "pin", "expired-packet",
                System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2));

        var result = bridge.ingest(packetFor(instruction), "bridge-a", 1);

        assertEquals("INVALID", result.outcome());
        assertEquals("stale_packet", result.reason());
        assertEquals(0, transactions.count());
        assertFalse(paymentNonces.existsByNonce("expired-packet"));
    }

    @Test
    void futureDatedPacketIsRejectedBeforeSettlement() throws Exception {
        PaymentInstruction instruction = new PaymentInstruction(
                "alice@demo", "bob@demo", new BigDecimal("10.00"), "pin", "future-packet",
                System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(6));

        var result = bridge.ingest(packetFor(instruction), "bridge-a", 1);

        assertEquals("INVALID", result.outcome());
        assertEquals("future_dated", result.reason());
        assertEquals(0, transactions.count());
        assertFalse(paymentNonces.existsByNonce("future-packet"));
    }

    @Test
    void insufficientBalanceCreatesRejectedTransactionWithoutMovingMoney() throws Exception {
        BigDecimal aliceBefore = accounts.findById("alice@demo").orElseThrow().getBalance();
        BigDecimal bobBefore = accounts.findById("bob@demo").orElseThrow().getBalance();
        MeshPacket packet = demoService.createPacket("alice@demo", "bob@demo",
                aliceBefore.add(BigDecimal.ONE), "1234", 5);

        var result = bridge.ingest(packet, "bridge-a", 1);

        assertEquals("REJECTED", result.outcome());
        assertEquals("insufficient_balance", result.reason());
        assertEquals(aliceBefore, accounts.findById("alice@demo").orElseThrow().getBalance());
        assertEquals(bobBefore, accounts.findById("bob@demo").orElseThrow().getBalance());
        assertEquals(1, transactions.count());
    }

    @Test
    void unknownSenderIsRejectedBeforeSettlement() throws Exception {
        PaymentInstruction instruction = new PaymentInstruction(
                "missing@demo", "bob@demo", new BigDecimal("10.00"), "pin", "unknown-sender",
                System.currentTimeMillis());

        var result = bridge.ingest(packetFor(instruction), "bridge-a", 1);

        assertEquals("INVALID", result.outcome());
        assertEquals("unknown_sender", result.reason());
        assertEquals(0, transactions.count());
        assertFalse(paymentNonces.existsByNonce("unknown-sender"));
    }

    @Test
    void concurrentDifferentPaymentsCanRetryAfterOptimisticLockConflict() throws Exception {
        BigDecimal aliceBefore = accounts.findById("alice@demo").orElseThrow().getBalance();
        BigDecimal bobBefore = accounts.findById("bob@demo").orElseThrow().getBalance();
        MeshPacket firstPacket = demoService.createPacket("alice@demo", "bob@demo", new BigDecimal("100.00"), "1234", 5);
        MeshPacket secondPacket = demoService.createPacket("alice@demo", "bob@demo", new BigDecimal("100.00"), "1234", 5);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<BridgeIngestionService.IngestResult> firstResult = new AtomicReference<>();
        AtomicReference<BridgeIngestionService.IngestResult> secondResult = new AtomicReference<>();
        Future<?> first = pool.submit(() -> { start.await(); firstResult.set(bridge.ingest(firstPacket, "bridge-a", 1)); return null; });
        Future<?> second = pool.submit(() -> { start.await(); secondResult.set(bridge.ingest(secondPacket, "bridge-b", 1)); return null; });

        start.countDown();
        first.get(5, TimeUnit.SECONDS);
        second.get(5, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(1, java.util.stream.Stream.of(firstResult.get(), secondResult.get())
                .filter(result -> "SETTLED".equals(result.outcome())).count());
        assertEquals(1, java.util.stream.Stream.of(firstResult.get(), secondResult.get())
                .filter(result -> "RETRYABLE_ERROR".equals(result.outcome())).count());
        assertEquals(aliceBefore.subtract(new BigDecimal("100.00")), accounts.findById("alice@demo").orElseThrow().getBalance());
        assertEquals(bobBefore.add(new BigDecimal("100.00")), accounts.findById("bob@demo").orElseThrow().getBalance());

        MeshPacket retryPacket = "RETRYABLE_ERROR".equals(firstResult.get().outcome()) ? firstPacket : secondPacket;
        var retry = bridge.ingest(retryPacket, "bridge-retry", 1);
        assertEquals("SETTLED", retry.outcome());
        assertEquals(aliceBefore.subtract(new BigDecimal("200.00")), accounts.findById("alice@demo").orElseThrow().getBalance());
        assertEquals(bobBefore.add(new BigDecimal("200.00")), accounts.findById("bob@demo").orElseThrow().getBalance());
    }

    @Test
    void tamperedCiphertextIsRejected() throws Exception {
        MeshPacket packet = demoService.createPacket(
                "alice@demo", "bob@demo", new BigDecimal("50.00"), "1234", 5);

        // Flip a byte in the middle of the ciphertext
        char[] chars = packet.getCiphertext().toCharArray();
        chars[chars.length / 2] = chars[chars.length / 2] == 'A' ? 'B' : 'A';
        packet.setCiphertext(new String(chars));

        BridgeIngestionService.IngestResult r = bridge.ingest(packet, "bridge-x", 1);
        assertEquals("INVALID", r.outcome());
    }

    @Test
    void encryptDecryptRoundTrip() throws Exception {
        PaymentInstruction original = new PaymentInstruction(
                "alice@demo", "bob@demo", new BigDecimal("123.45"),
                "abcdef", "nonce-1", System.currentTimeMillis());

        String ct = crypto.encrypt(original, serverKey.getPublicKey());
        PaymentInstruction decrypted = crypto.decrypt(ct);

        assertEquals(original.getSenderVpa(), decrypted.getSenderVpa());
        assertEquals(original.getReceiverVpa(), decrypted.getReceiverVpa());
        assertEquals(0, original.getAmount().compareTo(decrypted.getAmount()));
        assertEquals(original.getNonce(), decrypted.getNonce());
    }

    @Test
    void serverKeyPairPersistsAcrossRestart() throws Exception {
        ServerKeyHolder first = new ServerKeyHolder(temporaryKeyDirectory);
        first.init();
        String firstPublicKey = first.getPublicKeyBase64();

        ServerKeyHolder restarted = new ServerKeyHolder(temporaryKeyDirectory);
        restarted.init();

        assertEquals(firstPublicKey, restarted.getPublicKeyBase64());
    }

    @Test
    void invalidBridgeRequestReturnsStructuredValidationError() throws Exception {
        mockMvc.perform(post("/api/bridge/ingest")
                        .header("X-Bridge-Api-Key", "test-bridge-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"packetId\":\"\",\"ttl\":-1,\"ciphertext\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"))
                .andExpect(jsonPath("$.details.packetId").exists())
                .andExpect(jsonPath("$.details.ttl").exists());
    }

    @Test
    void bridgeIngestionRejectsMissingApiKey() throws Exception {
        mockMvc.perform(post("/api/bridge/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_bridge_credentials"));
    }

    @Test
    void invalidDemoAmountReturnsStructuredValidationError() throws Exception {
        mockMvc.perform(post("/api/demo/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"senderVpa\":\"alice@demo\",\"receiverVpa\":\"bob@demo\",\"amount\":10.001,\"pin\":\"1234\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"))
                .andExpect(jsonPath("$.details.amount").exists());
    }

    private MeshPacket packetFor(PaymentInstruction instruction) throws Exception {
        MeshPacket packet = new MeshPacket();
        packet.setPacketId(java.util.UUID.randomUUID().toString());
        packet.setTtl(5);
        packet.setCreatedAt(System.currentTimeMillis());
        packet.setCiphertext(crypto.encrypt(instruction, serverKey.getPublicKey()));
        return packet;
    }
}
