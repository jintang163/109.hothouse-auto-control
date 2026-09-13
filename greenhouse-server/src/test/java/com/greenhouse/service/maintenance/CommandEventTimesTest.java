package com.greenhouse.service.maintenance;

import com.greenhouse.entity.ControlCommand;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** 指令事件时间回退链：ackedAt → sentAt → createdAt → fallback */
class CommandEventTimesTest {

    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 9, 12, 23, 58);
    private static final LocalDateTime SENT = LocalDateTime.of(2026, 9, 12, 23, 59);
    private static final LocalDateTime ACKED = LocalDateTime.of(2026, 9, 13, 0, 0, 30);
    private static final LocalDateTime FALLBACK = LocalDateTime.of(2026, 9, 13, 12, 0);

    private ControlCommand cmd(LocalDateTime createdAt, LocalDateTime sentAt, LocalDateTime ackedAt) {
        ControlCommand cmd = new ControlCommand();
        cmd.setCreatedAt(createdAt);
        cmd.setSentAt(sentAt);
        cmd.setAckedAt(ackedAt);
        return cmd;
    }

    @Test
    void ackedAtWinsOverOthers() {
        assertThat(CommandEventTimes.of(cmd(CREATED, SENT, ACKED), FALLBACK)).isEqualTo(ACKED);
    }

    @Test
    void fallsBackToSentAtWhenNoAck() {
        assertThat(CommandEventTimes.of(cmd(CREATED, SENT, null), FALLBACK)).isEqualTo(SENT);
    }

    @Test
    void fallsBackToCreatedAtWhenNoAckNoSent() {
        assertThat(CommandEventTimes.of(cmd(CREATED, null, null), FALLBACK)).isEqualTo(CREATED);
    }

    @Test
    void fallsBackToGivenFallbackWhenAllNull() {
        assertThat(CommandEventTimes.of(cmd(null, null, null), FALLBACK)).isEqualTo(FALLBACK);
    }
}
