package com.zhicai.platform.identity;

/** Persistence record. Never serialize the password hash to clients. */
public record AccountRow(String id, String username, String passwordHash, String displayName,
                         AccountType accountType, boolean enabled, long version) {}
