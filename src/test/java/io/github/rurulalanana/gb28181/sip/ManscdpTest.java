package io.github.rurulalanana.gb28181.sip;

import io.github.rurulalanana.gb28181.sip.constant.ManscdpTypes;
import io.github.rurulalanana.gb28181.sip.manscdp.CatalogItem;
import io.github.rurulalanana.gb28181.sip.manscdp.ManscdpBuilder;
import io.github.rurulalanana.gb28181.sip.manscdp.ManscdpMessage;
import io.github.rurulalanana.gb28181.sip.manscdp.ManscdpXml;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * MANSCDP 消息构造与解析测试（附录 A）。
 */
public class ManscdpTest {

    private static final String CAMERA = "34020000001320000001";
    private static final String PLATFORM = "34020000002000000001";

    @Test
    public void 心跳保活结构应符合A252() {
        String xml = ManscdpBuilder.keepalive(1, CAMERA);
        ManscdpMessage m = ManscdpXml.parse(xml);
        // 根元素为 Notify
        assertEquals("Notify", m.getRootName());
        assertEquals("Keepalive", m.getCmdType());
        assertEquals(1, m.getSn());
        assertEquals(CAMERA, m.getDeviceId());
        assertEquals("OK", m.firstText("Status"));
    }

    @Test
    public void 心跳可携带故障设备列表() {
        String xml = ManscdpBuilder.keepalive(2, PLATFORM, ManscdpTypes.RESULT_OK,
                java.util.Arrays.asList(CAMERA, PLATFORM));
        ManscdpMessage m = ManscdpXml.parse(xml);
        assertEquals("Notify", m.getRootName());
        // 单个 Info 容器，内含 2 个 DeviceID
        assertEquals(1, m.elements("Info").size());
        assertEquals(Integer.valueOf(2), m.containerNum("Info"));
        assertEquals(2, m.indexChildren(m.first("Info")).get("DeviceID").size());
    }

    @Test
    public void 目录查询结构应符合A243() {
        String xml = ManscdpBuilder.catalogQuery(10, PLATFORM);
        ManscdpMessage m = ManscdpXml.parse(xml);
        assertEquals("Query", m.getRootName());
        assertEquals("Catalog", m.getCmdType());
        assertEquals(10, m.getSn());
        assertEquals(PLATFORM, m.getDeviceId());
        // 可选字段未设置时不应输出
        assertEquals(null, m.firstText("StartTime"));
    }

    @Test
    public void 目录查询带时间范围() {
        String xml = ManscdpBuilder.catalogQuery(11, PLATFORM, "2026-01-01T00:00:00", "2026-12-31T23:59:59");
        ManscdpMessage m = ManscdpXml.parse(xml);
        assertEquals("2026-01-01T00:00:00", m.firstText("StartTime"));
        assertEquals("2026-12-31T23:59:59", m.firstText("EndTime"));
    }

    @Test
    public void 目录应答SumNum为必选() {
        // A.2.6.4: SumNum 必选，即使 DeviceList 为空也必须出现，取 0
        String xml = ManscdpBuilder.emptyCatalogResponse(20, PLATFORM);
        ManscdpMessage m = ManscdpXml.parse(xml);
        assertEquals("Response", m.getRootName());
        assertEquals("Catalog", m.getCmdType());
        assertEquals(20, m.getSn());
        assertEquals("0", m.firstText("SumNum"));
        assertEquals(0, m.items("DeviceList").size());
    }

    @Test
    public void 目录应答条目应完整() {
        CatalogItem item = CatalogItem.builder()
                .deviceId(CAMERA)
                .name("前端摄像机01")
                .manufacturer("海康威视")
                .model("DS-2CD2143G2")
                .civilCode("340200")
                .block("340200")
                .address("滨湖路1号")
                .parental(0)
                .parentId(PLATFORM)
                .registerWay(1)
                .secrecy(0)
                .status("ON")
                .longitude(113.123456)
                .latitude(23.123456)
                .ipAddress("192.168.1.64")
                .port(5060)
                .ptzType(1)
                .grassrootsCode("000000")
                .build();

        String xml = ManscdpBuilder.catalogResponse(21, PLATFORM,
                java.util.Collections.singletonList(item));
        ManscdpMessage m = ManscdpXml.parse(xml);

        assertEquals("Response", m.getRootName());
        assertEquals("Catalog", m.getCmdType());
        // SumNum 必须与 DeviceList/@Num 一致
        assertEquals("1", m.firstText("SumNum"));
        assertEquals(Integer.valueOf(1), m.containerNum("DeviceList"));

        assertEquals(1, m.items("DeviceList").size());
        CatalogItem parsed = CatalogItem.from(m.items("DeviceList").get(0));
        assertEquals(CAMERA, parsed.getDeviceId());
        assertEquals("前端摄像机01", parsed.getName());
        assertEquals("海康威视", parsed.getManufacturer());
        assertEquals("340200", parsed.getCivilCode());
        assertEquals("ON", parsed.getStatus());
        assertEquals(Integer.valueOf(0), parsed.getParental());
        assertEquals(Integer.valueOf(1), parsed.getPtzType());
        assertEquals("000000", parsed.getGrassrootsCode());
        assertEquals(Double.valueOf(113.123456), parsed.getLongitude());
    }

    @Test
    public void 目录主动推送必须是Notify而不是Response() {
        /*
         * 这两个根节点的区别是「有没有人在等」：
         *   Response 是对某个查询的答复，对端按 (SN, DeviceID) 匹配它自己发出的查询，
         *            匹配不上就静默丢弃 —— 双方日志都看不到错误，只有对端通道数为 0。
         *   Notify   是主动通知，不需要有人提问。
         *
         * 实测 LiveGBS 就是这个行为：用 Response 推送目录，它既不报错也不落库。
         * 所以这里把「推送用 Notify」固化成断言，避免以后又被改回去。
         */
        CatalogItem item = CatalogItem.builder()
                .deviceId(CAMERA)
                .name("前端摄像机01")
                .event("ON")
                .build();
        String xml = ManscdpBuilder.catalogNotify(21, PLATFORM,
                java.util.Collections.singletonList(item));
        ManscdpMessage m = ManscdpXml.parse(xml);

        assertEquals("Notify", m.getRootName());
        assertEquals("Catalog", m.getCmdType());
        // SumNum 与 DeviceList/@Num 必须一致，且空列表时也要出现
        assertEquals("1", m.firstText("SumNum"));
        assertEquals(Integer.valueOf(1), m.containerNum("DeviceList"));
        // Event 必填：对端据此判断新增/更新/删除，缺了会被当成非法条目丢弃
        assertEquals("ON", CatalogItem.from(m.items("DeviceList").get(0)).getEvent());
    }

    @Test
    public void 设备状态应答四套枚举不可混用() {
        // A.2.6.6: Result=OK/ERROR, Online=ONLINE/OFFLINE, Status=OK/ERROR, Encode/Record=ON/OFF
        String xml = ManscdpBuilder.deviceStatusResponse(30, CAMERA,
                ManscdpTypes.RESULT_OK, ManscdpTypes.OFFLINE, ManscdpTypes.RESULT_ERROR,
                "网络异常", ManscdpTypes.STATUS_ON, ManscdpTypes.STATUS_OFF);
        ManscdpMessage m = ManscdpXml.parse(xml);
        assertEquals("OK", m.firstText("Result"));
        assertEquals("OFFLINE", m.firstText("Online"));
        assertEquals("ERROR", m.firstText("Status"));
        assertEquals("网络异常", m.firstText("Reason"));
        assertEquals("ON", m.firstText("Encode"));
        assertEquals("OFF", m.firstText("Record"));
    }

    @Test
    public void 设备信息应答Result位于DeviceName之后() {
        String xml = ManscdpBuilder.deviceInfoResponse(31, CAMERA, "OK", "前端摄像机",
                "海康威视", "DS-2CD", "V5.7.3", 2);
        ManscdpMessage m = ManscdpXml.parse(xml);
        assertEquals("DeviceInfo", m.getCmdType());
        assertEquals("前端摄像机", m.firstText("DeviceName"));
        assertEquals("OK", m.firstText("Result"));
        assertEquals("V5.7.3", m.firstText("Firmware"));
        assertEquals("2", m.firstText("Channel"));
        // 字段顺序：Result 必须紧跟 DeviceName
        assertTrue(xml.indexOf("DeviceName") < xml.indexOf("Result"));
    }

    @Test
    public void PTZ控制使用8字符PTZCmd() {
        // A.2.3.1.2: 2022 版 PTZ 控制码为 PTZCmd 字符串
        String ptz = io.github.rurulalanana.gb28181.sip.constant.PtzCommand.builder()
                .address(1).left().build().encode();
        String xml = ManscdpBuilder.ptzControl(40, CAMERA, ptz);
        ManscdpMessage m = ManscdpXml.parse(xml);
        assertEquals("Control", m.getRootName());
        assertEquals("DeviceControl", m.getCmdType());
        assertEquals(ptz, m.firstText("PTZCmd"));
    }

    @Test
    public void 语音广播使用SourceID与TargetID() {
        // A.2.5.5: 广播通知用 SourceID/TargetID，不是 DeviceID
        String xml = ManscdpBuilder.broadcast(41, CAMERA, PLATFORM);
        ManscdpMessage m = ManscdpXml.parse(xml);
        assertEquals("Notify", m.getRootName());
        assertEquals("Broadcast", m.getCmdType());
        assertEquals(CAMERA, m.firstText("SourceID"));
        assertEquals(PLATFORM, m.firstText("TargetID"));
        assertEquals(null, m.firstText("DeviceID"));
    }

    @Test
    public void 巡航轨迹查询Number为必选() {
        String xml = ManscdpBuilder.cruiseTrackQuery(50, CAMERA, 3);
        ManscdpMessage m = ManscdpXml.parse(xml);
        assertEquals("CruiseTrackQuery", m.getCmdType());
        assertEquals("3", m.firstText("Number"));
    }

    @Test
    public void 文件检索时间必选() {
        String xml = ManscdpBuilder.recordInfoQuery(51, CAMERA,
                "2026-01-01T00:00:00", "2026-01-02T00:00:00");
        ManscdpMessage m = ManscdpXml.parse(xml);
        assertEquals("RecordInfo", m.getCmdType());
        assertEquals("2026-01-01T00:00:00", m.firstText("StartTime"));
        assertEquals("2026-01-02T00:00:00", m.firstText("EndTime"));
    }

    @Test
    public void 应解析设备私有扩展字段() {
        // 设备厂商常带 SafetyWay 等 2016 版遗留字段，松散模型应原样保留
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Notify><CmdType>Keepalive</CmdType><SN>1</SN>"
                + "<DeviceID>" + CAMERA + "</DeviceID><Status>OK</Status>"
                + "<VendorExt>自定义内容</VendorExt></Notify>";
        ManscdpMessage m = ManscdpXml.parse(xml);
        assertEquals("自定义内容", m.firstText("VendorExt"));
    }

    @Test
    public void 应拒绝外部实体以防XXE() {
        String evil = "<?xml version=\"1.0\"?>"
                + "<!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<Notify><CmdType>&xxe;</CmdType></Notify>";
        try {
            ManscdpXml.parse(evil);
            // 解析失败亦可接受，关键是不得读取本地文件
        } catch (Exception expected) {
            // DOCTYPE 被禁用，抛出预期异常
        }
    }

    @Test
    public void 输出应带XML声明且为UTF8() {
        String xml = ManscdpBuilder.keepalive(60, CAMERA);
        assertTrue(xml.startsWith("<?xml"));
        assertTrue(xml.contains("UTF-8"));
        assertNotNull(xml);
    }

    @Test
    public void 级联转发控制应只替换SN并保留指令语义() {
        // 上级点云台的原始报文（PTZCmd 是 8 字节不透明串，绝不能解码后重编码）
        String fromParent = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Control><CmdType>DeviceControl</CmdType><SN>9527</SN>"
                + "<DeviceID>" + CAMERA + "</DeviceID>"
                + "<PTZCmd>A50F0101A0000000</PTZCmd></Control>";
        ManscdpMessage src = ManscdpXml.parse(fromParent);

        String forwarded = ManscdpBuilder.relinkControl(4242, src);
        ManscdpMessage out = ManscdpXml.parse(forwarded);

        // SN 必须换成我们自己的（上级的 SN 是它的事务号，撞号会让设备应答错位）
        assertEquals(4242, out.getSn());
        // 通道编码与指令原样保留
        assertEquals(CAMERA, out.getDeviceId());
        assertEquals("A50F0101A0000000", out.firstText("PTZCmd"));
        assertEquals("DeviceControl", out.getCmdType());
        assertEquals("Control", out.getRootName());
    }

    @Test
    public void 级联转发控制应保持XSD字段顺序() {
        // 国标设备对 sequence 顺序严格校验，CmdType 必须在 SN 之前
        String fromParent = "<Control><CmdType>DeviceControl</CmdType><SN>1</SN>"
                + "<DeviceID>" + CAMERA + "</DeviceID>"
                + "<PTZCmd>A50F0101A0000000</PTZCmd></Control>";
        String forwarded = ManscdpBuilder.relinkControl(2, ManscdpXml.parse(fromParent));
        int cmdType = forwarded.indexOf("CmdType");
        int sn = forwarded.indexOf("<SN>");
        int deviceId = forwarded.indexOf("DeviceID");
        int ptzCmd = forwarded.indexOf("PTZCmd");
        assertTrue("CmdType 应在 SN 之前", cmdType < sn);
        assertTrue("SN 应在 DeviceID 之前", sn < deviceId);
        assertTrue("DeviceID 应在 PTZCmd 之前", deviceId < ptzCmd);
    }

    @Test
    public void 级联转发除云台外的控制指令也应原样带上() {
        // 预置位删除这类指令与 PTZCmd 完全不同的字段集，必须同样能转发
        String fromParent = "<Control><CmdType>DeviceControl</CmdType><SN>7</SN>"
                + "<DeviceID>" + CAMERA + "</DeviceID>"
                + "<PresetCmd>Del</PresetCmd><PresetID>12</PresetID></Control>";
        ManscdpMessage out = ManscdpXml.parse(
                ManscdpBuilder.relinkControl(8, ManscdpXml.parse(fromParent)));
        assertEquals("Del", out.firstText("PresetCmd"));
        assertEquals("12", out.firstText("PresetID"));
        assertEquals(8, out.getSn());
    }
}