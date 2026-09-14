package com.greenhouse.service.farm;

/**
 * 处方环境目标区间（envTargetsJson 的一项）。
 *
 * @param metric    指标：temperature / humidity / light / co2
 * @param low       目标下限（含）
 * @param high      目标上限（含）
 * @param tolerance 允许绝对偏差（超出区间再多走该值才判定越界，类似死区，默认 0）
 * @param unit      展示单位
 */
public record EnvTarget(String metric, Double low, Double high, Double tolerance, String unit) {

    /** 偏差方向 */
    public enum Direction { HIGH, LOW }

    /** 一次越界判定结果 */
    public record Deviation(EnvTarget target, Direction direction, double value, double bound) {

        public String metric() {
            return target.metric();
        }

        public String unit() {
            return target.unit();
        }

        public String describe() {
            return switch (metricDisplay()) {
                case "温度" -> String.format("温度 %.1f℃ %s 目标 %.1f℃",
                        value(), direction == Direction.HIGH ? "高于" : "低于", bound());
                case "湿度" -> String.format("湿度 %.1f%% %s 目标 %.1f%%",
                        value(), direction == Direction.HIGH ? "高于" : "低于", bound());
                case "光照" -> String.format("光照 %.0flux %s 目标 %.0flux",
                        value(), direction == Direction.HIGH ? "高于" : "低于", bound());
                case "CO₂" -> String.format("CO₂ %.0fppm %s 目标 %.0fppm",
                        value(), direction == Direction.HIGH ? "高于" : "低于", bound());
                default -> String.format("%s %.2f %s %s %.2f",
                        metric(), value(), unit(), direction == Direction.HIGH ? ">" : "<", bound());
            };
        }

        public String metricDisplay() {
            return switch (target.metric()) {
                case "temperature" -> "温度";
                case "humidity" -> "湿度";
                case "light" -> "光照";
                case "co2" -> "CO₂";
                default -> target.metric();
            };
        }
    }

    public double toleranceOrZero() {
        return tolerance == null ? 0.0 : tolerance;
    }

    /**
     * 纯函数：当前值与目标区间对比，越出「区间 ± 死区」才返回偏差（null 表示在目标范围内/无数据）。
     */
    public Deviation check(Double value) {
        if (value == null || (low == null && high == null)) {
            return null;
        }
        double t = toleranceOrZero();
        if (high != null && value > high + t) {
            return new Deviation(this, Direction.HIGH, value, high);
        }
        if (low != null && value < low - t) {
            return new Deviation(this, Direction.LOW, value, low);
        }
        return null;
    }
}
