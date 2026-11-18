package com.zhicai.platform.identity;

import java.util.List;
public record AccountView(String id, String username, String displayName, AccountType accountType,
                          List<String> permissions, List<String> warehouseIds, List<String> roleIds,
                          boolean enabled, long version) {}
