package io.github.rurulalanana.gb28181.sip.cascade;

import io.github.rurulalanana.gb28181.sip.manscdp.CatalogItem;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.util.List;

/**
 * 级联事件。
 *
 * <p>与 {@code SipCallback} 的设备级事件分离——上级平台关心的是"下级的动态"，
 * 而非本级设备细节。
 */
public final class CascadeEvents {

    private CascadeEvents() {
    }

    /** 下级平台注册（含续注册）。 */
    @Getter
    @Builder
    @ToString
    public static class PlatformRegistered {
        /** 下级平台编码。 */
        private final String platformId;
        /** 对端地址 {@code ip:port}。 */
        private final String address;
        /** 注册有效期（秒）。 */
        private final int expires;
        /** 是否为续注册（此前已在线）。 */
        private final boolean refresh;
    }

    /** 下级平台注销（主动注销或超时下线）。 */
    @Getter
    @Builder
    @ToString
    public static class PlatformUnregistered {
        /** 下级平台编码。 */
        private final String platformId;
        /** 下线原因：{@code expired} / {@code logout} / {@code silent}。 */
        private final String reason;
    }

    /**
     * 级联目录查询完成（已聚合所有在线下级）。
     *
     * <p>流转发不在本模块处理；上级点播下级时通过
     * {@code SipCallback#onCascadeInvite} 回调把协商交还调用方。
     */
    @Getter
    @Builder
    @ToString
    public static class CatalogMerged {
        /** 调用方原始 SN。 */
        private final int sn;
        /** 聚合后的设备总数（已按编码去重）。 */
        private final int totalCount;
        /** 已成功应答的下级平台编码。 */
        private final List<String> respondedPlatforms;
        /** 超时或失败的下级平台编码。 */
        private final List<String> failedPlatforms;
        /** 聚合结果，仅回调给业务方；需主动发给上级时用
         * {@code SipCallback#onCascadeCatalog} 提供的 XML。 */
        private final List<CatalogItem> items;
        /** 可直接回复上级的 Catalog 应答 XML。 */
        private final String responseXml;
    }

    /** 上级请求级联点播（需业务方准备收流地址并应答）。 */
    @Getter
    @Builder
    @ToString
    public static class CascadeInvite {
        /** 上级平台编码。 */
        private final String platformId;
        /** SSRC。 */
        private final String ssrc;
        /** 收流地址 {@code ip:port}（由业务方分配并填入应答）。 */
        private final String receiveAddress;
        /** 是否为历史回放。 */
        private final boolean playback;
        /** 底层事件，调用方可用 {@code client().ackInvite(...)} 应答。 */
        private final javax.sip.RequestEvent rawRequestEvent;
    }
}