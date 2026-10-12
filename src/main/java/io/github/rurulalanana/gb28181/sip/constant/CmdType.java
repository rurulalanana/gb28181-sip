package io.github.rurulalanana.gb28181.sip.constant;

/**
 * GB/T 28181—2022 附录 A 定义的全部 MANSCDP 命令类型。
 *
 * <p>命名严格对齐标准正文，<b>不做"看起来更合理"的改名</b>。例如 2016 版常见的
 * {@code PTZPreciseStatus} / {@code StorageCardStatus} / {@code CruiseQuery} 在 2022 版
 * 中并不存在，标准实际使用的是 {@link #PTZPosition} / {@link #SDCardStatus} /
 * {@link #CruiseTrackQuery}。
 *
 * @see <a href="https://openstd.samr.gov.cn/">GB/T 28181—2022 附录 A</a>
 */
public enum CmdType {

    // ---------- Control（A.2.3）----------
    /** 设备控制：PTZ、远程启动、录像控制、布撤防、报警复位、强制关键帧、拉框缩放、看守位、PTZ 精准控制、升级、格式化、目标跟踪。 */
    DEVICE_CONTROL("DeviceControl"),

    // ---------- Query（A.2.4）----------
    /** 设备状态查询。 */
    DEVICE_STATUS("DeviceStatus"),
    /** 设备目录查询或订阅。 */
    CATALOG("Catalog"),
    /** 设备信息查询。 */
    DEVICE_INFO("DeviceInfo"),
    /** 文件目录检索（历史音视频）。 */
    RECORD_INFO("RecordInfo"),
    /** 报警订阅。 */
    ALARM("Alarm"),
    /** 设备配置查询。注意：一次查询可返回多个 Response，SN 相同。 */
    CONFIG_DOWNLOAD("ConfigDownload"),
    /** 设备预置位查询。 */
    PRESET_QUERY("PresetQuery"),
    /** 移动设备位置数据订阅。 */
    MOBILE_POSITION("MobilePosition"),
    /** 看守位信息查询。 */
    HOME_POSITION_QUERY("HomePositionQuery"),
    /** 巡航轨迹<b>列表</b>查询。 */
    CRUISE_TRACK_LIST_QUERY("CruiseTrackListQuery"),
    /** 巡航轨迹查询（按 Number 取单条轨迹的点位）。 */
    CRUISE_TRACK_QUERY("CruiseTrackQuery"),
    /** PTZ 精确状态查询或订阅。 */
    PTZ_POSITION("PTZPosition"),
    /** 存储卡状态查询。 */
    SD_CARD_STATUS("SDCardStatus"),

    // ---------- Notify（A.2.5）----------
    /** 状态信息报送（心跳保活）。 */
    KEEPALIVE("Keepalive"),
    /** 媒体通知。 */
    MEDIA_STATUS("MediaStatus"),
    /** 语音广播通知。 */
    BROADCAST("Broadcast"),
    /** 图像抓拍传输完成通知。 */
    UPLOAD_SNAPSHOT_FINISHED("UploadSnapShotFinished"),
    /** 设备实时视音频回传通知。 */
    VIDEO_UPLOAD_NOTIFY("VideoUploadNotify"),
    /** 设备软件升级结果通知。 */
    DEVICE_UPGRADE_RESULT("DeviceUpgradeResult"),

    // ---------- Response 专有（A.2.6）----------
    /** 设备配置应答。2022 版中该 CmdType 仅用于应答，无对应 Query。 */
    DEVICE_CONFIG("DeviceConfig"),

    // ---------- 2016 版兼容 ----------
    /**
     * 媒体流播放控制。
     *
     * @deprecated <b>GB/T 28181—2022 已删除该命令</b>，附录 A 中无对应 XSD 定义。
     *     但存量设备（尤其是 2016 版平台）大量依赖它，故作为可选兼容扩展保留。
     *     使用时调用方需自行确认对端支持，可通过
     *     {@link io.github.rurulalanana.gb28181.sip.Gb28181SipServerBuilder#enableLegacyMediaControl(boolean)} 开关控制。
     */
    @Deprecated
    MEDIA_CONTROL("MediaControl");

    private final String value;

    CmdType(String value) {
        this.value = value;
    }

    /** 标准中书写的字面量。 */
    public String value() {
        return value;
    }

    /** 按字面量反查；未知命令返回 {@code null}（设备厂商私扩命令很常见，不应抛异常）。 */
    public static CmdType of(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        for (CmdType t : values()) {
            if (t.value.equalsIgnoreCase(v)) {
                return t;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return value;
    }
}