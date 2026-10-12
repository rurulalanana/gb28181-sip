package io.github.rurulalanana.gb28181.sip.constant;

/**
 * GB/T 28181—2022 附录 A.2.1 中的全局简单类型与常见枚举取值。
 *
 * <p><b>关键提醒：</b>标准中并存多套取值域相近但<b>互不相同</b>的枚举，混用会导致对端解析失败。
 * 典型反例是 {@code DeviceStatus} 应答里的四个字段：
 * <ul>
 *   <li>{@code Result} → {@code resultType}：OK / ERROR</li>
 *   <li>{@code Online} → 内联枚举：ONLINE / OFFLINE</li>
 *   <li>{@code Status} → {@code resultType}：OK / ERROR</li>
 *   <li>{@code Encode} / {@code Record} → {@code statusType}：ON / OFF</li>
 * </ul>
 */
public final class ManscdpTypes {

    private ManscdpTypes() {
    }

    // ================= tg:SNType（A.2.1.3） =================
    /** 命令序列号下界，标准规定 minInclusive = 1。 */
    public static final int SN_MIN = 1;
    /** 命令序列号上界，标准规定 maxInclusive = 9999。 */
    public static final int SN_MAX = 9999;

    /**
     * 生成合法 SN。SN 必须落在 [1, 9999]，超范围是国标协议错误，
     * 很多设备会直接丢弃报文。
     */
    public static int nextSn(java.util.concurrent.atomic.AtomicInteger counter) {
        int v = Math.floorMod(counter.getAndIncrement(), SN_MAX) + 1;
        return v;
    }

    // ================= tg:resultType（A.2.1.15） =================
    /** 结果类型：成功。 */
    public static final String RESULT_OK = "OK";
    /** 结果类型：失败。 */
    public static final String RESULT_ERROR = "ERROR";

    // ================= tg:statusType（A.2.1.14） =================
    /** 状态：开 / 在线 / 工作。 */
    public static final String STATUS_ON = "ON";
    /** 状态：关 / 离线。 */
    public static final String STATUS_OFF = "OFF";

    // ================= DeviceStatus 应答专有枚举 =================
    /** {@code Online} 字段取值：在线。注意是 ONLINE 而非 ON。 */
    public static final String ONLINE = "ONLINE";
    /** {@code Online} 字段取值：离线。 */
    public static final String OFFLINE = "OFFLINE";

    /** {@code Alarmstatus/Item/DutyStatus} 取值：值班。 */
    public static final String DUTY_ONDUTY = "ONDUTY";
    /** {@code Alarmstatus/Item/DutyStatus} 取值：空值班。 */
    public static final String DUTY_OFFDUTY = "OFFDUTY";
    /** {@code Alarmstatus/Item/DutyStatus} 取值：报警。 */
    public static final String DUTY_ALARM = "ALARM";

    // ================= SDCardStatusInfo/Item/Status（A.2.6.16） =================
    /** 存储卡状态：正常。注意本项为小写，与其他 ON/OFF 风格不同。 */
    public static final String SD_OK = "ok";
    /** 存储卡状态：格式化中。 */
    public static final String SD_FORMATTING = "formatting";
    /** 存储卡状态：未格式化。 */
    public static final String SD_UNFORMATTED = "unformatted";
    /** 存储卡状态：空闲。 */
    public static final String SD_IDLE = "idle";
    /** 存储卡状态：错误。 */
    public static final String SD_ERROR = "error";

    // ================= Content-Type =================
    /** MANSCDP 消息体的 Content-Type（标准 9.5.3.1 / 9.11.1.3 / 9.11.3.3 明确要求）。 */
    public static final String CONTENT_TYPE_MANSCDP = "Application/MANSCDP+xml";

    // ================= 事件类型（A.2.4.6 / 9.11） =================
    /** 订阅事件：目录（RFC 6665 事件包名）。 */
    public static final String EVENT_CATALOG = "Catalog";
    /** 订阅事件：报警。 */
    public static final String EVENT_ALARM = "Alarm";
    /** 订阅事件：移动位置。 */
    public static final String EVENT_MOBILE_POSITION = "MobilePosition";
    /** 订阅事件：PTZ 精确位置变化。 */
    public static final String EVENT_PTZ_POSITION = "PTZPosition";

    // ================= 注册缺省值（9.1.1 d） =================
    /** 注册有效期缺省值 86400 秒（1 天）。 */
    public static final int DEFAULT_REGISTER_EXPIRES = 86400;
    /** 注册刷新间隔缺省值 3600 秒。 */
    public static final int DEFAULT_REFRESH_INTERVAL = 3600;
}