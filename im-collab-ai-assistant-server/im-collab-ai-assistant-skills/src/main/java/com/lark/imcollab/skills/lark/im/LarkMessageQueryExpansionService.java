package com.lark.imcollab.skills.lark.im;

import java.util.List;

@FunctionalInterface
public interface LarkMessageQueryExpansionService {

    List<String> expandQueries(
            String userQuery,
            String originalQuery,
            String startTime,
            String endTime,
            int maxQueries
    );
}
