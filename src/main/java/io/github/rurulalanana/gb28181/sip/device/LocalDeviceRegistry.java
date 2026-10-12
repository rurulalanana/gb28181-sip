package io.github.rurulalanana.gb28181.sip.device;

import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内存设备注册表（单机默认实现）。
 *
 * <p>基于 {@link ConcurrentHashMap}，线程安全。
 */
@Slf4j
public class LocalDeviceRegistry implements DeviceRegistry {

    private final Map<String, DeviceSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void register(DeviceSession session) {
        if (session == null || session.getDeviceId() == null) {
            return;
        }
        DeviceSession prev = sessions.put(session.getDeviceId(), session.touch());
        if (prev == null) {
            log.info("设备注册: {} @ {}", session.getDeviceId(), session.getAddress());
        } else {
            log.debug("设备刷新注册: {} @ {}", session.getDeviceId(), session.getAddress());
        }
    }

    @Override
    public DeviceSession unregister(String deviceId) {
        DeviceSession removed = sessions.remove(deviceId);
        if (removed != null) {
            log.info("设备注销: {}", deviceId);
        }
        return removed;
    }

    @Override
    public DeviceSession get(String deviceId) {
        return deviceId == null ? null : sessions.get(deviceId);
    }

    @Override
    public boolean touch(String deviceId) {
        DeviceSession s = get(deviceId);
        if (s == null) {
            return false;
        }
        s.touch();
        return true;
    }

    @Override
    public Collection<DeviceSession> listAll() {
        return sessions.values();
    }

    @Override
    public List<String> listByDomain(String domain) {
        return sessions.values().stream()
                .filter(s -> domain == null || domain.equals(s.getDomain()))
                .map(DeviceSession::getDeviceId)
                .collect(Collectors.toList());
    }

    @Override
    public int size() {
        return sessions.size();
    }

    @Override
    public void clear() {
        sessions.clear();
    }
}