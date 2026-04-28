package com.lark.imcollab.store.checkpoint;

import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;

public interface CheckpointSaverProvider {

    BaseCheckpointSaver getCheckpointSaver();
}
