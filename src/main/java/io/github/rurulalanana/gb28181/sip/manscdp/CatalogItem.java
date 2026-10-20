package io.github.rurulalanana.gb28181.sip.manscdp;

import lombok.Builder;
import lombok.Getter;
import org.w3c.dom.Element;

/**
 * 设备目录项（附录 A {@code tg:itemType}）。
 *
 * <p>用 Lombok {@code @Builder} 提供链式构建，避免手写 36 个字段的构造器：
 * <pre>{@code
 * CatalogItem item = CatalogItem.builder()
 *         .deviceId("34020000001320000001")
 *         .name("前端摄像机01")
 *         .manufacturer("海康威视")
 *         .civilCode("340200")
 *         .parental(0)
 *         .parentId("34020000001110000001")
 *         .registerWay(1)
 *         .secrecy(0)
 *         .status("ON")
 *         .longitude(113.123456)
 *         .latitude(23.123456)
 *         .ptzType(1)
 *         .build();
 * }</pre>
 *
 * <p>字段顺序严格按标准 XSD sequence，{@link #appendTo(Element)} 依此输出。
 * 未设置的可选字段不输出。
 */
@Getter
@Builder(toBuilder = true)
public class CatalogItem {

    // ---------- 顶层必选字段 ----------
    /** 设备编码。 */
    private final String deviceId;
    /** 设备/区域/系统名称。 */
    private final String name;
    /** 设备厂商。 */
    private final String manufacturer;
    /** 设备型号。 */
    private final String model;
    /** 行政区划（2/4/6/8 位）。XSD 未给 minOccurs，实际必选。 */
    private final String civilCode;
    /** 安装地址。 */
    private final String address;
    /** 是否有父子设备：0=无，1=有。 */
    private final Integer parental;
    /** 父节点 ID，可多值用 {@code /} 分隔。 */
    private final String parentId;
    /** 注册方式：1=RFC3261，2=口令，3=证书双向，4=证书单向。 */
    private final Integer registerWay;
    /** 保密属性：0=不涉密，1=涉密。 */
    private final Integer secrecy;
    /** 设备状态：{@code ON} / {@code OFF}。 */
    private final String status;
    /** 经度，WGS 84。 */
    private final Double longitude;
    /** 纬度，WGS 84。 */
    private final Double latitude;

    // ---------- 顶层可选字段 ----------
    /** 警区。 */
    private final String block;
    /** 摄像机安全能力等级：A/B/C。 */
    private final String securityLevelCode;
    /** 设备 IPv4/IPv6。 */
    private final String ipAddress;
    /** 设备端口。 */
    private final Integer port;
    /** 设备口令。 */
    private final String password;
    /** 业务分组 ID。 */
    private final String businessGroupId;

    // ---------- Info 内可选字段（摄像机扩展） ----------
    /** 摄像机结构类型：1=球机，2=半球，3=固定枪机，4=遥控枪机，5=遥控半球，6=多目全景，7=多目分视。 */
    private final Integer ptzType;
    /** 光电成像类型。 */
    private final Integer photoelectricImagingType;
    /** 采集部位类型（附录 O）。 */
    private final String capturePositionType;
    /** 室内外：1=室外，2=室内。 */
    private final Integer roomType;
    /** 补光类型：1=无，2=红外，3=白光，4=激光，9=其他。 */
    private final Integer supplyLightType;
    /** 监视视方位 1~8。 */
    private final Integer directionType;
    /** 支持的分辨率，可多值 {@code /} 分隔。 */
    private final String resolution;
    /** 支持的码流编号列表，如 {@code 0/1/2}。 */
    private final String streamNumberList;
    /** 下载倍速，如 {@code 1/2/4}。 */
    private final String downloadSpeed;
    /** 水平视角（度）。 */
    private final Double horizontalAngle;
    /** 竖直视场角（度）。 */
    private final Double verticalFieldAngle;
    /** 可视距离（米）。 */
    private final Double maxViewDistance;
    /**
     * 基层组织编码（附录 E.3）。<b>Info 内唯一必选字段</b>，非基层建设填 {@code "000000"}。
     */
    private final String grassrootsCode;
    /** 点位类型：1=一类，2=二类，3=三类，9=其他。 */
    private final Integer pointType;
    /** 设备 MAC，形如 {@code AA-BB-CC-DD-EE-FF}。 */
    private final String mac;
    /** 视口功能类型，如 {@code 01} 人脸卡口。 */
    private final String functionType;
    /** 视频编码格式。 */
    private final String encodeType;
    /** 安装使用时间（dateTime）。 */
    private final String installTime;
    /** 所属管理单位名称。 */
    private final String managementUnit;
    /** 联系人联系方式。 */
    private final String contactInfo;
    /** 录像保存天数。 */
    private final Integer recordSaveDays;
    /**
     * 目录项变更类型（附录 A.2.4，仅通知 {@code Notify} 中出现）。
     *
     * <p>取值：{@code ADD} 新增 / {@code UPDATE} 更新 / {@code DELETE} 删除 /
     * {@code ON} 上线 / {@code OFF} 下线。查询应答（{@code Response}）里没有该字段。
     *
     * <p><b>不处理会怎样</b>：下级平台删除通道时推送 {@code <Event>DELETE</Event>}，
     * 若一律当作「新增/更新」upsert，下级已删除的通道会被本级<b>重新加回来</b>，
     * 形成永远删不掉的幽灵通道。
     */
    private final String event;

    /**
     * 把当前目录项追加到 {@code <DeviceList>} 下。
     *
     * @param parent {@code <DeviceList>} 或 {@code <DeviceList>} 的父节点
     * @return 新建的 {@code <Item>} 元素
     */
    public Element appendTo(Element parent) {
        Element item = ManscdpXml.newElement(parent, "Item");
        // ---- 顶层顺序（严格对齐 XSD sequence） ----
        ManscdpXml.addText(item, "DeviceID", deviceId);
        ManscdpXml.addText(item, "Name", name);
        ManscdpXml.addText(item, "Manufacturer", manufacturer);
        ManscdpXml.addText(item, "Model", model);
        ManscdpXml.addText(item, "CivilCode", civilCode);
        ManscdpXml.addText(item, "Block", block);
        ManscdpXml.addText(item, "Address", address);
        ManscdpXml.addText(item, "Parental", parental);
        ManscdpXml.addText(item, "ParentID", parentId);
        ManscdpXml.addText(item, "RegisterWay", registerWay);
        ManscdpXml.addText(item, "SecurityLevelCode", securityLevelCode);
        ManscdpXml.addText(item, "Secrecy", secrecy);
        ManscdpXml.addText(item, "IPAddress", ipAddress);
        ManscdpXml.addText(item, "Port", port);
        ManscdpXml.addText(item, "Password", password);
        ManscdpXml.addText(item, "Status", status);
        ManscdpXml.addText(item, "Longitude", longitude);
        ManscdpXml.addText(item, "Latitude", latitude);
        ManscdpXml.addText(item, "BusinessGroupID", businessGroupId);

        // ---- Info 容器：任一字段设置才输出 ----
        Element info = buildInfo(item);
        if (info != null) {
            item.appendChild(info);
        }
        // ---- Event：XSD 中位于 Info 之后，仅通知类报文携带 ----
        ManscdpXml.addText(item, "Event", event);
        return item;
    }

    private Element buildInfo(Element item) {
        boolean any = ptzType != null || photoelectricImagingType != null
                || capturePositionType != null || roomType != null || supplyLightType != null
                || directionType != null || resolution != null || streamNumberList != null
                || downloadSpeed != null || horizontalAngle != null || verticalFieldAngle != null
                || maxViewDistance != null || pointType != null || mac != null
                || functionType != null || encodeType != null || installTime != null
                || managementUnit != null || contactInfo != null || recordSaveDays != null
                || grassrootsCode != null;
        if (!any) {
            return null;
        }
        Element info = ManscdpXml.newElement(item, "Info");
        ManscdpXml.addText(info, "PTZType", ptzType);
        ManscdpXml.addText(info, "PhotoelectricImagingType", photoelectricImagingType);
        ManscdpXml.addText(info, "CapturePositionType", capturePositionType);
        ManscdpXml.addText(info, "RoomType", roomType);
        ManscdpXml.addText(info, "SupplyLightType", supplyLightType);
        ManscdpXml.addText(info, "DirectionType", directionType);
        ManscdpXml.addText(info, "Resolution", resolution);
        ManscdpXml.addText(info, "StreamNumberList", streamNumberList);
        ManscdpXml.addText(info, "DownloadSpeed", downloadSpeed);
        ManscdpXml.addText(info, "HorizontalAngle", horizontalAngle);
        ManscdpXml.addText(info, "VerticalFieldAngle", verticalFieldAngle);
        ManscdpXml.addText(info, "MaxViewDistance", maxViewDistance);
        // GrassrootsCode 在 Info 内必选，未显式设置时按标准填 000000
        ManscdpXml.addText(info, "GrassrootsCode",
                grassrootsCode == null ? "000000" : grassrootsCode);
        ManscdpXml.addText(info, "PointType", pointType);
        ManscdpXml.addText(info, "MAC", mac);
        ManscdpXml.addText(info, "FunctionType", functionType);
        ManscdpXml.addText(info, "EncodeType", encodeType);
        ManscdpXml.addText(info, "InstallTime", installTime);
        ManscdpXml.addText(info, "ManagementUnit", managementUnit);
        ManscdpXml.addText(info, "ContactInfo", contactInfo);
        ManscdpXml.addText(info, "RecordSaveDays", recordSaveDays);
        return info;
    }

    /**
     * 从已解析的 {@code <Item>} 元素回读。
     *
     * @param item 已解析的 DOM 元素
     * @return 目录项实例
     */
    public static CatalogItem from(Element item) {
        Element info = firstChild(item, "Info");
        return CatalogItem.builder()
                .deviceId(text(item, "DeviceID"))
                .name(text(item, "Name"))
                .manufacturer(text(item, "Manufacturer"))
                .model(text(item, "Model"))
                .civilCode(text(item, "CivilCode"))
                .block(text(item, "Block"))
                .address(text(item, "Address"))
                .parental(intOf(item, "Parental"))
                .parentId(text(item, "ParentID"))
                .registerWay(intOf(item, "RegisterWay"))
                .securityLevelCode(text(item, "SecurityLevelCode"))
                .secrecy(intOf(item, "Secrecy"))
                .ipAddress(text(item, "IPAddress"))
                .port(intOf(item, "Port"))
                .password(text(item, "Password"))
                .status(text(item, "Status"))
                .longitude(dblOf(item, "Longitude"))
                .latitude(dblOf(item, "Latitude"))
                .businessGroupId(text(item, "BusinessGroupID"))
                .ptzType(info == null ? null : intOf(info, "PTZType"))
                .photoelectricImagingType(info == null ? null : intOf(info, "PhotoelectricImagingType"))
                .capturePositionType(info == null ? null : text(info, "CapturePositionType"))
                .roomType(info == null ? null : intOf(info, "RoomType"))
                .supplyLightType(info == null ? null : intOf(info, "SupplyLightType"))
                .directionType(info == null ? null : intOf(info, "DirectionType"))
                .resolution(info == null ? null : text(info, "Resolution"))
                .streamNumberList(info == null ? null : text(info, "StreamNumberList"))
                .downloadSpeed(info == null ? null : text(info, "DownloadSpeed"))
                .horizontalAngle(info == null ? null : dblOf(info, "HorizontalAngle"))
                .verticalFieldAngle(info == null ? null : dblOf(info, "VerticalFieldAngle"))
                .maxViewDistance(info == null ? null : dblOf(info, "MaxViewDistance"))
                .grassrootsCode(info == null ? null : text(info, "GrassrootsCode"))
                .pointType(info == null ? null : intOf(info, "PointType"))
                .mac(info == null ? null : text(info, "MAC"))
                .functionType(info == null ? null : text(info, "FunctionType"))
                .encodeType(info == null ? null : text(info, "EncodeType"))
                .installTime(info == null ? null : text(info, "InstallTime"))
                .managementUnit(info == null ? null : text(info, "ManagementUnit"))
                .contactInfo(info == null ? null : text(info, "ContactInfo"))
                .recordSaveDays(info == null ? null : intOf(info, "RecordSaveDays"))
                .event(text(item, "Event"))
                .build();
    }

    // ---------- DOM 小工具（供本类复用） ----------

    private static Element firstChild(Element parent, String name) {
        for (org.w3c.dom.Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && name.equals(n.getNodeName())) {
                return (Element) n;
            }
        }
        return null;
    }

    private static String text(Element parent, String name) {
        Element e = firstChild(parent, name);
        return e == null ? null : ManscdpMessage.textOf(e);
    }

    private static Integer intOf(Element parent, String name) {
        String v = text(parent, name);
        if (v == null) {
            return null;
        }
        try {
            return Integer.valueOf(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double dblOf(Element parent, String name) {
        String v = text(parent, name);
        if (v == null) {
            return null;
        }
        try {
            return Double.valueOf(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}