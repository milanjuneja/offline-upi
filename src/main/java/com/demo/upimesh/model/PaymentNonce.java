package com.demo.upimesh.model;

import jakarta.persistence.*;

import java.time.Instant;

/** A nonce identifies one payment instruction across all ciphertext variants. */
@Entity
@Table(name = "payment_nonces", uniqueConstraints =
        @UniqueConstraint(name = "uk_payment_nonce", columnNames = "nonce"))
public class PaymentNonce {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String nonce;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public void setNonce(String nonce) {
        this.nonce = nonce;
    }
}
