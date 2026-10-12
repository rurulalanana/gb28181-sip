package io.github.rurulalanana.gb28181.sip.manscdp;

import lombok.Getter;
import org.apache.commons.collections4.ListUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MANSCDP 消息体模型。
 *
 * <p>采用<b>松散 DOM + 便捷访问器</b>而非强绑定 POJO：
 * <ol>
 *   <li>GB/T 28181—2022 附录 A 只给 XSD Schema，未给实例 XML，强绑定易在字段顺序/必选性上与真实设备冲突。</li>
 *   <li>设备厂商普遍存在私有扩展字段（2016 版遗留的 {@code SafetyWay}、{@code Transversal} 等），
 *       松散模型可原样保留而不丢失。</li>
 * </ol>
 * 根元素固定四类：{@code Control} / {@code Query} / {@code Notify} / {@code Response}。
 */
@Getter
public class ManscdpMessage {

    private final String rootName;
    private final String cmdType;
    private final int sn;
    private final Map<String, List<Element>> children;

    /** 供 {@link #from(Document)} 内部构建。 */
    private ManscdpMessage(String rootName, String cmdType, int sn,
                           Map<String, List<Element>> children) {
        this.rootName = rootName;
        this.cmdType = cmdType;
        this.sn = sn;
        this.children = children;
    }

    /**
     * 命令类型的枚举视图；设备私有命令返回 {@code null}。
     *
     * @see io.github.rurulalanana.gb28181.sip.constant.CmdType
     */
    public io.github.rurulalanana.gb28181.sip.constant.CmdType cmdTypeEnum() {
        return io.github.rurulalanana.gb28181.sip.constant.CmdType.of(cmdType);
    }

    /** 设备编码，取 {@code <DeviceID>}；广播类命令无此字段。 */
    public String getDeviceId() {
        return firstText("DeviceID");
    }

    /**
     * 取第一个同名子元素的文本。
     *
     * @return 不存在返回 {@code null}
     */
    public String firstText(String name) {
        Element e = first(name);
        return e == null ? null : textOf(e);
    }

    /** 取同名子元素列表，永不为 {@code null}。 */
    public List<Element> elements(String name) {
        return ListUtils.emptyIfNull(children.get(name));
    }

    /** 所有直接子元素名（去重，保持出现顺序）。 */
    public List<String> childNames() {
        return new ArrayList<>(children.keySet());
    }

    /** 取第一个同名子元素。 */
    public Element first(String name) {
        return ListUtils.emptyIfNull(children.get(name)).stream().findFirst().orElse(null);
    }

    /**
     * 沿路径取子元素，如 {@code child("Info", "AlarmType")}。
     * 仅查直接子元素，避免误命中 {@code Item/Info/PTZType} 这类同名字段。
     *
     * @return 任一层缺失即返回 {@code null}
     */
    public Element child(String... path) {
        if (path == null || path.length == 0) {
            return null;
        }
        Element cur = first(path[0]);
        for (int i = 1; i < path.length && cur != null; i++) {
            cur = firstChild(cur, path[i]);
        }
        return cur;
    }

    /**
     * 沿路径取文本，如 {@code childText("Info", "AlarmType")}。
     *
     * @return 不存在返回 {@code null}
     */
    public String childText(String... path) {
        return textOf(child(path));
    }

    /**
     * 取列表容器下的全部 {@code <Item>}。
     *
     * @param containerName 如 {@code DeviceList} / {@code RecordList}
     * @return 元素列表，无数据返回空列表
     */
    public List<Element> items(String containerName) {
        Element c = first(containerName);
        return c == null ? new ArrayList<>() : childrenNamed(c, "Item");
    }

    /** 取列表容器的 {@code @Num} 属性。 */
    public Integer containerNum(String containerName) {
        Element c = first(containerName);
        if (c == null || !c.hasAttribute("Num")) {
            return null;
        }
        try {
            return Integer.valueOf(c.getAttribute("Num"));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 取文本子元素的整数形式。 */
    public Integer intOf(String name) {
        return toInt(firstText(name));
    }

    // ==================== 解析入口 ====================

    /**
     * 从 DOM 文档构建。
     *
     * @param doc 已解析的 XML 文档
     */
    public static ManscdpMessage from(Document doc) {
        Element root = doc.getDocumentElement();
        Map<String, List<Element>> children = new LinkedHashMap<>();
        children.putAll(indexChildren(root));
        String cmdType = textOf(ListUtils.emptyIfNull(children.get("CmdType")).stream()
                .findFirst().orElse(null));
        int sn = 0;
        String snText = textOf(ListUtils.emptyIfNull(children.get("SN")).stream()
                .findFirst().orElse(null));
        if (snText != null) {
            try {
                sn = Integer.parseInt(snText.trim());
            } catch (NumberFormatException ignore) {
                sn = 0;
            }
        }
        return new ManscdpMessage(root.getNodeName(), cmdType, sn, children);
    }

    // ==================== 内部工具 ====================

    /** 建立"元素名 → 元素列表"索引，保持文档顺序。 */
    public static Map<String, List<Element>> indexChildren(Element parent) {
        Map<String, List<Element>> m = new LinkedHashMap<>();
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n instanceof Element) {
                List<Element> l = m.computeIfAbsent(n.getNodeName(), k -> new ArrayList<>());
                l.add((Element) n);
            }
        }
        return m;
    }

    private static List<Element> childrenNamed(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n instanceof Element && name.equals(n.getNodeName())) {
                out.add((Element) n);
            }
        }
        return out;
    }

    private static Element firstChild(Element parent, String name) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && name.equals(n.getNodeName())) {
                return (Element) n;
            }
        }
        return null;
    }

    /** 提取元素的全部文本（含 CDATA）。 */
    public static String textOf(Element e) {
        if (e == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        NodeList nl = e.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            short t = n.getNodeType();
            if (t == Node.TEXT_NODE || t == Node.CDATA_SECTION_NODE) {
                sb.append(n.getNodeValue());
            }
        }
        return sb.toString().trim();
    }

    private static Integer toInt(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "ManscdpMessage{" + rootName + "/" + cmdType + ", sn=" + sn + "}";
    }
}