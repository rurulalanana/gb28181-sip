package io.github.rurulalanana.gb28181.sip.constant;

import lombok.Builder;
import lombok.Getter;

/**
 * PTZ 云台控制码生成器（GB/T 28181—2022 附录 A.3）。
 *
 * <p>2022 版 PTZ 控制是 <b>8 字符定长字符串</b>（{@code tg:PTZCmdType}, length=8），结构：
 * <pre>
 *   字节1  字节2      字节3    字节4    字节5  字节6  字节7      字节8
 *   A5H    组合码1     地址     指令     数据1  数据2  组合码2     校验码
 * </pre>
 * <ul>
 *   <li>字节2 = 高 4 位版本信息 + 低 4 位校验位</li>
 *   <li>字节7 = 高 4 位数据3 + 低 4 位地址高位</li>
 *   <li>字节8 = 前 7 字节算术和的低 8 位（对 256 取模）</li>
 *   <li>地址范围 001H~FFFH</li>
 * </ul>
 *
 * <p>标准约束：Bit5/Bit4 不同时为 1；Bit3/Bit2 不同时为 1；Bit1/Bit0 不同时为 1；
 * 镜头变倍、云台上下、云台左右三者<b>可以</b>组合。
 *
 * <p>用法：
 * <pre>{@code
 * String ptz = PtzCommand.builder()
 *         .address(1)                    // 0001H
 *         .zoomIn().up().left()          // 变倍 + 上 + 左
 *         .horizSpeed(6).vertSpeed(6).zoomSpeed(6)
 *         .build().encode();
 * }</pre>
 */
@Getter
@Builder
public class PtzCommand {

    /** 固定首字节。 */
    public static final String HEADER = "A5";
    /** 控制码总长度。 */
    public static final int LENGTH = 8;

    /** 版本信息（字节2 高 4 位）。 */
    private final Integer version;
    /** 地址（字节3 + 字节7 低 4 位），范围 1~4095。 */
    private final Integer address;
    /** 水平（左右）码速。 */
    private final Integer horizSpeed;
    /** 垂直（上下）码速。 */
    private final Integer vertSpeed;
    /** 镜头变倍码速。 */
    private final Integer zoomSpeed;

    // ---------- 指令位（字节4） ----------
    private boolean left;
    private boolean right;
    private boolean up;
    private boolean down;
    private boolean zoomIn;
    private boolean zoomOut;
    /** 预置位调用。 */
    private boolean presetCall;
    /** 预置位设置。 */
    private boolean presetSet;
    /** 预置位删除。 */
    private boolean presetRemove;
    /** 巡航启动。 */
    private boolean cruiseStart;
    /** 巡航停止。 */
    private boolean cruiseStop;
    /** 巡航暂停。 */
    private boolean cruisePause;
    /** 巡航继续。 */
    private boolean cruiseResume;
    /** 巡航参数设置。 */
    private boolean cruiseSet;
    /** 字符叠加开关。 */
    private boolean charOver;
    /** 焦距切换。 */
    private boolean focus;
    /** 预置位巡航（watchdog）。 */
    private boolean watchdog;
    /** 数据3（第 7 字节高 4 位）。 */
    private Integer data3;

    // ---------- 组合码1 的低 4 位：校验位 ----------
    private boolean checksum1;
    private boolean checksum2;
    private boolean checksum3;
    private boolean checksum4;

    // ==================== 便捷方法 ====================

    public PtzCommand left() {
        this.left = true;
        return this;
    }

    public PtzCommand right() {
        this.right = true;
        return this;
    }

    public PtzCommand up() {
        this.up = true;
        return this;
    }

    public PtzCommand down() {
        this.down = true;
        return this;
    }

    public PtzCommand zoomIn() {
        this.zoomIn = true;
        return this;
    }

    public PtzCommand zoomOut() {
        this.zoomOut = true;
        return this;
    }

    public PtzCommand presetCall() {
        this.presetCall = true;
        return this;
    }

    public PtzCommand presetSet() {
        this.presetSet = true;
        return this;
    }

    public PtzCommand presetRemove() {
        this.presetRemove = true;
        return this;
    }

    public PtzCommand cruiseStart() {
        this.cruiseStart = true;
        return this;
    }

    public PtzCommand cruiseStop() {
        this.cruiseStop = true;
        return this;
    }

    /**
     * {@link PtzCommandBuilder} 的链式扩展。
     *
     * <p>Lombok 生成的 builder 不继承实例方法，故在此显式提供，
     * 使 {@code PtzCommand.builder().address(1).up().left().build()} 可用。
     */
    public static class PtzCommandBuilder {

        /** 云台向左。 */
        public PtzCommandBuilder left(boolean v) {
            this.left = v;
            return this;
        }

        /** 链式便捷写法：{@code .left()} 等价于 {@code .left(true)}。 */
        public PtzCommandBuilder left() {
            return left(true);
        }

        /** 云台向右。 */
        public PtzCommandBuilder right(boolean v) {
            this.right = v;
            return this;
        }

        /** 链式便捷写法：{@code .right()} 等价于 {@code .right(true)}。 */
        public PtzCommandBuilder right() {
            return right(true);
        }

        /** 云台上仰。 */
        public PtzCommandBuilder up(boolean v) {
            this.up = v;
            return this;
        }

        /** 链式便捷写法：{@code .up()} 等价于 {@code .up(true)}。 */
        public PtzCommandBuilder up() {
            return up(true);
        }

        /** 云台下俯。 */
        public PtzCommandBuilder down(boolean v) {
            this.down = v;
            return this;
        }

        /** 链式便捷写法：{@code .down()} 等价于 {@code .down(true)}。 */
        public PtzCommandBuilder down() {
            return down(true);
        }

        /** 镜头变倍放大。 */
        public PtzCommandBuilder zoomIn(boolean v) {
            this.zoomIn = v;
            return this;
        }

        /** 链式便捷写法：{@code .zoomIn()} 等价于 {@code .zoomIn(true)}。 */
        public PtzCommandBuilder zoomIn() {
            return zoomIn(true);
        }

        /** 镜头变倍缩小。 */
        public PtzCommandBuilder zoomOut(boolean v) {
            this.zoomOut = v;
            return this;
        }

        /** 链式便捷写法：{@code .zoomOut()} 等价于 {@code .zoomOut(true)}。 */
        public PtzCommandBuilder zoomOut() {
            return zoomOut(true);
        }

        /** 调用预置位。 */
        public PtzCommandBuilder presetCall(boolean v) {
            this.presetCall = v;
            return this;
        }

        /** 链式便捷写法：{@code .presetCall()} 等价于 {@code .presetCall(true)}。 */
        public PtzCommandBuilder presetCall() {
            return presetCall(true);
        }

        /** 水平码速 0~255。 */
        public PtzCommandBuilder horizSpeed(Integer v) {
            this.horizSpeed = v;
            return this;
        }

        /** 垂直码速 0~255。 */
        public PtzCommandBuilder vertSpeed(Integer v) {
            this.vertSpeed = v;
            return this;
        }

        /** 变倍码速 0~15。 */
        public PtzCommandBuilder zoomSpeed(Integer v) {
            this.zoomSpeed = v;
            return this;
        }
    }

    // ==================== 编码 ====================

    /**
     * 编码为 8 字符控制码（附录 A.2.1.6 {@code tg:PTZCmdType}，{@code length=8}）。
     *
     * <p><b>长度说明</b>：XSD 约束为 <b>8 个十六进制字符</b>，而表 A.3 描述的是 <b>8 个字节</b>
     * （编码后 16 个字符）。二者在本标准中不一致。实际设备普遍按 <b>8 字节</b>（16 字符）收发，
     * 故本实现按表 A.3 编码为 16 个十六进制字符；若对端严格要求 8 字符，
     * 可调用 {@link #encodeHex4()}。
 *
     * @return 十六进制字符串
     * @throws IllegalArgumentException 地址越界或指令位组合违反标准约束
     */
    public String encode() {
        int addr = address == null ? 0 : address;
        if (addr < 0 || addr > 0xFFF) {
            throw new IllegalArgumentException("地址范围必须是 000H~FFFH(0~4095): " + addr);
        }

        // ---- 字节4：指令位 ----
        int cmd = 0;
        if (left) {
            cmd |= 0x01;
        }
        if (right) {
            cmd |= 0x02;
        }
        if (up) {
            cmd |= 0x04;
        }
        if (down) {
            cmd |= 0x08;
        }
        if (zoomIn) {
            cmd |= 0x10;
        }
        if (zoomOut) {
            cmd |= 0x20;
        }
        if (presetCall || presetSet || presetRemove
                || cruiseStart || cruiseStop || cruisePause || cruiseResume
                || cruiseSet || charOver || focus || watchdog) {
            cmd |= 0x40;
        }
        validateBitPairs(cmd);

        // ---- 字节2：高 4 位版本信息(本标准为 0H) + 低 4 位校验位 ----
        // 标准 A.3: 校验位 = (字节1的高4位 + 字节1的低4位 + 字节2的高4位) % 16
        int ver = (version == null ? 0 : version) & 0x0F;
        int c1 = (bit(checksum1) << 3) | (bit(checksum2) << 2)
                | (bit(checksum3) << 1) | bit(checksum4);
        int b1 = 0xA5;
        int check1 = ((b1 >> 4) + (b1 & 0x0F) + ver) % 16;
        int b2 = (ver << 4) | (check1 & 0x0F);

        // ---- 字节5/6：数据1 = 水平，字节6 = 垂直；变倍并入 字节7 高 4 位 ----
        int d1 = clamp(horizSpeed, 0xFF);
        int d2 = clamp(vertSpeed, 0xFF);
        // ---- 字节7：高 4 位数据3 + 低 4 位地址高位 ----
        int addrHigh = (addr >> 8) & 0x0F;
        int d3v = data3 != null ? data3 : clamp(zoomSpeed, 0x0F);
        int b7 = ((d3v & 0x0F) << 4) | addrHigh;

        int b3 = addr & 0xFF;
        // 字节8 = 前 7 字节算术和的低 8 位（标准 A.3）
        int b8 = (b1 + b2 + b3 + cmd + d1 + d2 + b7) % 256;

        return String.format("%02X%02X%02X%02X%02X%02X%02X%02X", b1, b2, b3, cmd, d1, d2, b7, b8);
    }

    /**
     * 按表 A.3 编码后<b>截取低 4 字节</b>，得到 XSD {@code length=8} 约束的 8 字符形式。
     *
     * <p>仅供对端严格要求 8 字符时使用；绝大多数设备按 8 字节（{@link #encode()}）收发。
     */
    public String encodeHex4() {
        String full = encode();
        return full.substring(full.length() - 8);
    }

    /**
     * 校验字节4 的成对约束：Bit5/Bit4、Bit3/Bit2、Bit1/Bit0 不应同时为 1。
     */
    private static void validateBitPairs(int cmd) {
        if ((cmd & 0x03) == 0x03) {
            throw new IllegalArgumentException("Bit1 与 Bit0 不应同时为 1（左/右互斥）");
        }
        if ((cmd & 0x0C) == 0x0C) {
            throw new IllegalArgumentException("Bit3 与 Bit2 不应同时为 1（上/下互斥）");
        }
        if ((cmd & 0x30) == 0x30) {
            throw new IllegalArgumentException("Bit5 与 Bit4 不应同时为 1（变倍放大/缩小互斥）");
        }
    }

    private static int bit(boolean b) {
        return b ? 1 : 0;
    }

    private static int clamp(Integer v, int max) {
        if (v == null) {
            return 0;
        }
        return Math.max(0, Math.min(v, max));
    }

    @Override
    public String toString() {
        return encode();
    }

    // ==================== 静态便捷方法 ====================

    /**
     * 预置位调用。
     *
     * @param presetIndex 预置位编号 0~255
     */
    public static String presetCall(int address, int presetIndex) {
        return PtzCommand.builder().address(address).presetCall(true)
                .data3(presetIndex & 0x0F).build().encode();
    }

    /** 广播地址（标准 A.3：000H 为广播地址，控制全部前端）。 */
    public static final int BROADCAST_ADDRESS = 0;

    /**
     * 广播控制（地址 000H），标准 A.3 定义的广播地址。
     *
     * @param ptz 已设置好指令位的 {@link PtzCommand}
     */
    public static String broadcast(PtzCommand ptz) {
        return PtzCommand.builder()
                .address(BROADCAST_ADDRESS)
                .left(ptz.isLeft()).right(ptz.isRight())
                .up(ptz.isUp()).down(ptz.isDown())
                .zoomIn(ptz.isZoomIn()).zoomOut(ptz.isZoomOut())
                .horizSpeed(ptz.getHorizSpeed())
                .vertSpeed(ptz.getVertSpeed())
                .zoomSpeed(ptz.getZoomSpeed())
                .build().encode();
    }

    /** 云台停止（清空所有运动指令位）。 */
    public static String stop(int address) {
        return PtzCommand.builder().address(address).build().encode();
    }
}