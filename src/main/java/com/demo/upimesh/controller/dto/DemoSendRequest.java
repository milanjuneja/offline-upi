package com.demo.upimesh.controller.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record DemoSendRequest(
        @NotBlank @Size(max = 255) String senderVpa,
        @NotBlank @Size(max = 255) String receiverVpa,
        @NotNull @DecimalMin(value = "0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
        @NotBlank @Size(max = 128) String pin,
        @Min(1) @Max(32) Integer ttl,
        @Size(max = 128) String startDevice
) {}
