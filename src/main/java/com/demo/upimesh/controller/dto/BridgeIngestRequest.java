package com.demo.upimesh.controller.dto;

import com.demo.upimesh.model.MeshPacket;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record BridgeIngestRequest(
        @NotBlank @Size(max = 128) String packetId,
        @NotNull @Min(0) Integer ttl,
        @NotNull @Min(0) Long createdAt,
        @NotBlank @Size(max = 16_384) String ciphertext
) {
    public MeshPacket toMeshPacket() {
        MeshPacket packet = new MeshPacket();
        packet.setPacketId(packetId);
        packet.setTtl(ttl);
        packet.setCreatedAt(createdAt);
        packet.setCiphertext(ciphertext);
        return packet;
    }
}
