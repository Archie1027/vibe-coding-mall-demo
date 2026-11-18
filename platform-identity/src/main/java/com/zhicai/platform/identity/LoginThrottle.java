package com.zhicai.platform.identity;

public interface LoginThrottle {
    Reservation reserve(String identity, String address);
    void failed(Reservation reservation);
    void succeeded(Reservation reservation);
    record Reservation(String identity, String address, String id) {}
}
