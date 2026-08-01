package eu.kanade.tachiyomi.ui.player.scene;

import eu.kanade.tachiyomi.ui.player.scene.ISceneCommandCallback;

interface ISceneCommandService {
    void execute(
        long requestId,
        int commandType,
        in String[] arguments,
        ISceneCommandCallback callback
    );

    void cancel(long requestId);
}
