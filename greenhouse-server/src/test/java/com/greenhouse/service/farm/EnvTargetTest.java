package com.greenhouse.service.farm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 处方环境目标区间偏差判定：区间 ± 死区（容忍度） */
class EnvTargetTest {

    private final EnvTarget temp = new EnvTarget("temperature", 22.0, 28.0, 1.0, "℃");

    @Test
    void withinRangeReturnsNull() {
        assertThat(temp.check(25.0)).isNull();
    }

    @Test
    void exactlyOnBoundsIsOk() {
        assertThat(temp.check(22.0)).isNull();
        assertThat(temp.check(28.0)).isNull();
    }

    @Test
    void withinToleranceDeadbandDoesNotDeviate() {
        // 上限 28 + 死区 1 = 29，28.7 仍在死区内
        assertThat(temp.check(28.7)).isNull();
        assertThat(temp.check(21.3)).isNull();
    }

    @Test
    void aboveHighPlusToleranceTriggersHigh() {
        EnvTarget.Deviation d = temp.check(30.5);
        assertThat(d).isNotNull();
        assertThat(d.direction()).isEqualTo(EnvTarget.Direction.HIGH);
        assertThat(d.value()).isEqualTo(30.5);
        assertThat(d.bound()).isEqualTo(28.0);
    }

    @Test
    void belowLowMinusToleranceTriggersLow() {
        EnvTarget.Deviation d = temp.check(20.0);
        assertThat(d).isNotNull();
        assertThat(d.direction()).isEqualTo(EnvTarget.Direction.LOW);
        assertThat(d.bound()).isEqualTo(22.0);
    }

    @Test
    void nullValueReturnsNull() {
        assertThat(temp.check(null)).isNull();
    }

    @Test
    void toleranceDefaultsToZero() {
        EnvTarget noDeadband = new EnvTarget("humidity", 60.0, 80.0, null, "%");
        assertThat(noDeadband.check(80.4)).isNotNull();
        assertThat(noDeadband.check(79.9)).isNull();
    }
}
