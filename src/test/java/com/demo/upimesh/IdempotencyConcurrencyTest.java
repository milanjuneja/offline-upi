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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The killer test: simulates the "three bridges deliver at the same instant"
 * scenario the user explicitly cared about.
 */
@SpringBootTest
class IdempotencyConcurrencyTest {

    @Autowired private DemoService demoService;
    @Autowired private BridgeIngestionService bridge;
    @Autowired private IdempotencyRecordRepository idempotencyRecords;
    @Autowired private TransactionRepository transactions;
    @Autowired private PaymentNonceRepository paymentNonces;
    @Autowired private AccountRepository accounts;
    @Autowired private HybridCryptoService crypto;
    @Autowired private ServerKeyHolder serverKey;

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

    private MeshPacket packetFor(PaymentInstruction instruction) throws Exception {
        MeshPacket packet = new MeshPacket();
        packet.setPacketId(java.util.UUID.randomUUID().toString());
        packet.setTtl(5);
        packet.setCreatedAt(System.currentTimeMillis());
        packet.setCiphertext(crypto.encrypt(instruction, serverKey.getPublicKey()));
        return packet;
    }
}
