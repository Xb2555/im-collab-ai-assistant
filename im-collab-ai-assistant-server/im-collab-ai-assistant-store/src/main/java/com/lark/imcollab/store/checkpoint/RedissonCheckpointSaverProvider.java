package com.lark.imcollab.store.checkpoint;

import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RedissonCheckpointSaverProvider implements CheckpointSaverProvider {

    private final ObjectProvider<BaseCheckpointSaver> checkpointSaverProvider;

    @Override
    public BaseCheckpointSaver getCheckpointSaver() {
        BaseCheckpointSaver checkpointSaver = checkpointSaverProvider.getIfAvailable();
        if (checkpointSaver != null) {
            return checkpointSaver;
        }
        return new MemorySaver();
    }
}
