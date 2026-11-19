package com.demo.upimesh.model;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentNonceRepository extends JpaRepository<PaymentNonce, Long> {
    boolean existsByNonce(String nonce);
}
