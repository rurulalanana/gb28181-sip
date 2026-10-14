package io.github.rurulalanana.gb28181.sip.manscdp;

import lombok.SneakyThrows;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.apache.commons.lang3.StringUtils;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.Charset;
import org.xml.sax.InputSource;

/**
 * MANSCDP XML 解析与序列化工具。
 *
 * <p>安全约定：解析时关闭外部实体与 DTD，防止 XXE。
 *
 * <p>编码约定：标准 6.10 规定信令字符集采用 GB 18030；但 GB 18030 是 GBK 的超集，
 * 对 GB/T 28181 实际使用的 ASCII 子集与 UTF-8 等价。为兼容存量设备，本模块
 * <b>按 UTF-8 输出，并容忍 UTF-8 / GBK 两种输入</b>（见 {@link #decode}）。
 */
public final class ManscdpXml {

    /** MANSCDP 消息体统一按 UTF-8 输出（GB 18030 的 ASCII 子集与 UTF-8 字节一致）。 */
    public static final Charset OUTPUT_CHARSET = Charset.forName("UTF-8");

    private ManscdpXml() {
    }

    /**
     * 解码 XML 字节流。
     * <p>先按 UTF-8 尝试，失败则回退 GBK。真实设备两种编码都存在。
     */
    public static String decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        String utf8 = new String(bytes, OUTPUT_CHARSET);
        // 出现替换字符说明不是合法 UTF-8，回退 GBK
        if (utf8.indexOf('�') >= 0) {
            return new String(bytes, Charset.forName("GBK"));
        }
        return utf8;
    }

    /**
     * 解析 XML 文本为 DOM。
     *
     * <p><b>必须用字符流（{@link StringReader}）而非字节流</b>：入参 {@code xml} 已经
     * 是解码好的字符串（见 {@link #decode}），若再 {@code getBytes()} 交给解析器，
     * 解析器会<b>按文档内的 {@code encoding} 声明</b>重新解码字节 ——
     * 设备普遍声明 {@code GB2312}（标准 6.10 规定 GB18030），而字节实际是 UTF-8，
     * 于是中文被二次解码成不可逆乱码（{@code 岳溪河娄家} → {@code 宀虫邯…}，
     * 且替换字符 {@code U+FFFD} 丢失原始信息）。
     *
     * <p>传入 {@link Reader} 时，XML 解析器直接使用该字符流，<b>忽略 encoding 声明</b>，
     * 从根本上避免二次解码。实测对照（声明 GB2312）：
     * <ul>
     *   <li>字节流：UTF-8 内容 → 乱码；GBK 内容 → 乱码；声明 GB18030 → 直接解析异常</li>
     *   <li>字符流：任意声明 → 均正确</li>
     * </ul>
     *
     * @throws IllegalArgumentException 文本为空或 XML 非法
     */
    public static Document parseDocument(String xml) {
        if (xml == null || xml.trim().isEmpty()) {
            throw new IllegalArgumentException("MANSCDP XML 不能为空");
        }
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            // 防 XXE
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            dbf.setXIncludeAware(false);
            dbf.setExpandEntityReferences(false);
            DocumentBuilder db = dbf.newDocumentBuilder();
            // 屏蔽解析器向 stderr 打印的错误
            db.setErrorHandler(null);
            return db.parse(new InputSource(new StringReader(xml.trim())));
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("XML 解析器初始化失败", e);
        } catch (Exception e) {
            throw new IllegalArgumentException("MANSCDP XML 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析为 {@link ManscdpMessage}。
     *
     * @throws IllegalArgumentException XML 为空或非法
     */
    public static ManscdpMessage parse(byte[] bytes) {
        return parse(decode(bytes));
    }

    /**
     * 解析为 {@link ManscdpMessage}。
     *
     * @throws IllegalArgumentException XML 为空或非法
     */
    public static ManscdpMessage parse(String xml) {
        return ManscdpMessage.from(parseDocument(xml));
    }

    /**
     * 序列化为 XML 文本，带 {@code <?xml version="1.0" encoding="UTF-8"?>} 声明。
     */
    public static String toXml(Document doc) {
        return doc == null ? null : doTransform(doc);
    }

    /** 序列化（根元素已在文档中）。 */
    @SneakyThrows
    public static String toXml(Element root) {
        return root == null ? null : doTransform(root.getOwnerDocument());
    }

    @SneakyThrows
    private static String doTransform(Document doc) {
        TransformerFactory tf = TransformerFactory.newInstance();
        tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        Transformer t = tf.newTransformer();
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        t.setOutputProperty(OutputKeys.INDENT, "no");
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        StringWriter w = new StringWriter();
        t.transform(new DOMSource(doc), new StreamResult(w));
        return w.toString();
    }

    /** 创建空白的 XML Document。 */
    public static Document newDocument() {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            return dbf.newDocumentBuilder().newDocument();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("XML 文档创建失败", e);
        }
    }

    /** 创建根元素。 */
    public static Element newRoot(Document doc, String name) {
        return doc.createElement(name);
    }

    /**
     * 创建元素并挂到 {@code parent} 下。
     *
     * @return 新建的元素
     */
    public static Element newElement(Element parent, String name) {
        Element e = parent.getOwnerDocument().createElement(name);
        parent.appendChild(e);
        return e;
    }

    /**
     * 追加文本子元素；{@code value} 为 {@code null} 或空串时不追加
     * （对应标准的 {@code minOccurs="0"} 省略语义）。
     *
     * @return 新建的元素；{@code value} 为空时返回 {@code null}
     */
    public static Element addText(Element parent, String name, Object value) {
        if (value == null) {
            return null;
        }
        String v = StringUtils.trimToNull(String.valueOf(value));
        if (v == null) {
            return null;
        }
        Element e = newElement(parent, name);
        e.setTextContent(v);
        return e;
    }

    /** 追加带 {@code Num} 属性的容器元素。 */
    public static Element addContainer(Element parent, String name, Integer num) {
        Element e = newElement(parent, name);
        if (num != null) {
            e.setAttribute("Num", String.valueOf(num));
        }
        return e;
    }
}