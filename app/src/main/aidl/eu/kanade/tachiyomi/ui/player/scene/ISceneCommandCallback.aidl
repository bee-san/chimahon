package eu.kanade.tachiyomi.ui.player.scene;

oneway interface ISceneCommandCallback {
    void onCompleted(long requestId, boolean success, String output);
}
