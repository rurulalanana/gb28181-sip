package io.github.rurulalanana.gb28181.sip;

import io.github.rurulalanana.gb28181.sip.constant.GbId;
import io.github.rurulalanana.gb28181.sip.sdp.SdpMediaFormat;
import io.github.rurulalanana.gb28181.sip.sdp.SdpMessage;
import io.github.rurulalanana.gb28181.sip.sdp.SdpParser;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * SDP 构造与解析测试（附录 G）。
 */
public class SdpTest {

    private static final String CAMERA = "34020000001320000001";

    @Test
    public void 必填字段应齐全() {
        // 附录 G: v= o= s= t= m= 为必选
        String text = new SdpMessage()
                .origin(CAMERA, "192.168.1.10")
                .connection("192.168.1.10")
                .sessionName(SdpMessage.S_PLAY)
                .realtime()
                .mediaName("video")
                .mediaPort(6000)
                .mediaProto(SdpMessage.PROTO_UDP)
                .payloadType("96")
                .recvonly()
                .toText();

        assertTrue(text.startsWith("v=0\r\n"));
        assertTrue(text.contains("o=" + CAMERA + " 0 0 IN IP4 192.168.1.10"));
        assertTrue(text.contains("s=Play"));
        assertTrue(text.contains("t=0 0"));
        assertTrue(text.contains("m=video 6000 RTP/AVP 96"));
        assertTrue(text.contains("a=recvonly"));
        // 行尾须为 CRLF
        assertTrue(text.contains("\r\n"));
    }

    @Test
    public void SSRC字段y应输出() {
        // 附录 G 的 y= 字段是 GB 特有扩展
        String ssrc = GbId.buildSsrc(CAMERA, 1, true);
        String text = new SdpMessage().ssrc(ssrc).toText();
        assertTrue(text.contains("y=" + ssrc));
        assertEquals(10, ssrc.length());
    }

    @Test
    public void 媒体描述f字段应保持完整结构() {
        // 标准注 15：音视频完整形态
        String f = SdpMediaFormat.h264Pip();
        assertEquals("v/2/6/25/1/2048/a/1/8/1", f);
        // 仅视频：保持 "v///a///" 结构
        String vOnly = SdpMediaFormat.videoOnly("2", "5", 25, 1, 1024);
        assertEquals("v/2/5/25/1/1024/a///", vOnly);
        // 仅音频：保持 "v///////a/..." 结构
        String aOnly = SdpMediaFormat.audioOnly("1", 8, 1);
        assertEquals("v//////a/1/8/1", aOnly);   // 标准注15 原文：v//////a/编码格式/码率大小/采样率
    }

    @Test
    public void f字段应可解析() {
        SdpMediaFormat.Parsed p = SdpMediaFormat.parse("v/2/6/25/1/2048/a/1/8/1");
        assertTrue(p.video);
        assertTrue(p.audio);
        assertEquals("2", p.videoCodec);
        assertEquals("6", p.resolution);
        assertEquals(Integer.valueOf(25), p.frameRate);
        assertEquals(Integer.valueOf(1), p.bitrateType);
        assertEquals(Integer.valueOf(2048), p.bitrate);
        assertEquals("1", p.audioCodec);
        assertEquals(Integer.valueOf(8), p.audioBitrate);
        assertEquals(Integer.valueOf(1), p.sampleRate);
    }

    @Test
    public void SDP解析应还原关键字段() {
        String raw = "v=0\r\n"
                + "o=34020000001320000001 0 0 IN IP4 192.168.1.64\r\n"
                + "s=Play\r\n"
                + "c=IN IP4 192.168.1.64\r\n"
                + "t=0 0\r\n"
                + "y=0200000001\r\n"
                + "f=v/2/6/25/1/2048/a/1/8/1\r\n"
                + "m=video 6000 RTP/AVP 96\r\n"
                + "a=recvonly\r\n"
                + "a=setup:passive\r\n";

        SdpMessage sdp = SdpParser.parse(raw);
        assertNotNull(sdp);
        assertEquals("Play", sdp.getSessionName());
        assertEquals("0200000001", sdp.getSsrc());
        assertEquals("v/2/6/25/1/2048/a/1/8/1", sdp.getMediaFormat());
        assertEquals("video", sdp.getMediaName());
        assertEquals(6000, sdp.getMediaPort());
        assertEquals("RTP/AVP", sdp.getMediaProto());
        assertEquals("96", sdp.getPayloadType());
        assertEquals("192.168.1.64", sdp.resolveMediaIp());
        assertEquals("passive", sdp.getSetup());
        assertTrue(sdp.getAttributes().contains("recvonly"));
    }

    @Test
    public void 回放SDP的s字段应为Playback() {
        // 附录 G: s 字段取值 Play=实时点播, Playback=历史回放, Download=下载
        SdpMessage sdp = new SdpMessage().sessionName(SdpMessage.S_PLAYBACK);
        assertTrue(sdp.toText().contains("s=Playback"));
        assertEquals("Playback", sdp.getSessionName());
    }

    @Test
    public void TCP承载应使用TCP_RTP_AVP() {
        String text = new SdpMessage().tcp(true).setup("active").toText();
        assertTrue(text.contains("m=video 6000 TCP/RTP/AVP 96"));
        assertTrue(text.contains("a=setup:active"));
    }

    @Test
    public void 应容忍设备非标准SDP() {
        // 真实设备常缺 v= / o= 行，或用 LF 换行
        String raw = "s=Play\r\nm=video 9000 RTP/AVP 96\r\ny=0200000001\r\n";
        SdpMessage sdp = SdpParser.parse(raw);
        assertNotNull(sdp);
        assertEquals(9000, sdp.getMediaPort());
        assertEquals("0200000001", sdp.getSsrc());
        // 缺失字段保持默认值而非抛异常
        assertNull(sdp.resolveMediaIp());
    }

    @Test
    public void 空输入应返回null() {
        assertNull(SdpParser.parse(null));
        assertNull(SdpParser.parse(""));
        assertNull(SdpParser.parse("   "));
    }

    @Test
    public void 码流编号与文件属性应输出() {
        String text = new SdpMessage()
                .streamNumber(1)
                .filesize(1048576)
                .downloadspeed(4)
                .toText();
        assertTrue(text.contains("a=streamnumber:1"));
        assertTrue(text.contains("a=filesize:1048576"));
        assertTrue(text.contains("a=downloadspeed:4"));
    }
}